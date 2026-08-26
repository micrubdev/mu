// A soundfont voice for the browser, fed by the same cycle feed as the HUD.
//
// API facts from node_modules/js-synthesizer 1.13.0 (read directly, not
// re-derived here):
//   - Construct with `new AudioWorkletNodeSynthesizer()`, then
//     `synth.init(context.sampleRate)`.
//   - `synth.createAudioNode(context)` returns the AudioWorkletNode. It
//     must be created before any other synth method is called -- the
//     message port to the worklet does not exist until then
//     (README.md:91-105).
//   - `synth.loadSFont(bin: ArrayBuffer): Promise<number>` loads an SF2.
//   - A sequencer with timestamped events exists: `synth.createSequencer()`
//     resolves an ISequencer; `sequencer.registerSynthesizer(synth)` wires
//     it to render; `sequencer.sendEventAt(event, tick, isAbsolute)` queues
//     one event. `setTimeScale` defaults to 1000 ticks/sec, i.e. one tick
//     is one millisecond, so the `atMs` values below are tick values used
//     with `isAbsolute: false` ("relative to now").
//   - `synth.midiAllNotesOff(chan?)` sends an immediate all-notes-off;
//     omitting `chan` covers every channel (it's passed through as -1).
//
// Timing note: events are handed over as soon as a cycle arrives, roughly
// a cycle ahead, and the sequencer's own scheduler places them. Nothing
// here runs on a JS timer -- a throttled timer in a background tab would
// turn into audible lateness, and the whole point of the one-cycle lead is
// to never need one.

import { AudioWorkletNodeSynthesizer } from 'js-synthesizer'

const LIBFLUIDSYNTH_URL = '/libfluidsynth-2.4.6.js'
const WORKLET_URL = '/js-synthesizer.worklet.js'

// Pure: scheduled audio-time events to timed MIDI events.
//
// atMs is rounded to the nearest integer: the sequencer's time scale is
// 1000 ticks/sec (one tick per ms, see the header comment), so a
// sub-millisecond value is not a real tick -- it's float noise from the
// subtraction in ctxTimeToMs (e.g. (10.1 - 10.0) * 1000 is
// 99.99999999999964, not 100). Rounding to the tick grain fixes that noise
// as a side effect.
export function toMidiEvents (events, ctxTimeToMs) {
  return events.map(e => {
    const atMs = Math.max(0, Math.round(ctxTimeToMs(e.time)))
    return e.type === 'note-on'
      ? { atMs, type: 'note-on', chan: e.chan, note: e.note,
          vel127: Math.round(Math.min(1, Math.max(0, e.vel)) * 127) }
      : { atMs, type: 'note-off', chan: e.chan, note: e.note }
  })
}

export function makeSynth (audioContext, { soundfontUrl }) {
  let synth = null
  let sequencer = null

  return {
    // "Ready" means fully wired -- a synth object that exists but never
    // finished loading its soundfont or registering its sequencer is not
    // ready, and schedule() below relies on this to decide whether to run.
    ready: () => Boolean(synth && sequencer),

    async start () {
      if (synth) return
      await audioContext.resume()
      try {
        // fetch() does not reject on a 404 -- it resolves with an error
        // page's bytes, which loadSFont would otherwise happily try to
        // parse as a soundfont. A missing .sf2 is the *documented* normal
        // case (see client/public/README.md), so this has to fail loud
        // rather than leave a half-built synth behind.
        const res = await fetch(soundfontUrl)
        if (!res.ok) {
          throw new Error(`soundfont fetch failed: ${res.status} ${soundfontUrl}`)
        }
        const sf2 = await res.arrayBuffer()

        await audioContext.audioWorklet.addModule(LIBFLUIDSYNTH_URL)
        await audioContext.audioWorklet.addModule(WORKLET_URL)
        synth = new AudioWorkletNodeSynthesizer()
        synth.init(audioContext.sampleRate)
        const node = synth.createAudioNode(audioContext)
        node.connect(audioContext.destination)
        await synth.loadSFont(sf2)
        sequencer = await synth.createSequencer()
        await sequencer.registerSynthesizer(synth)
      } catch (e) {
        // Leave no half-built state behind: ready() must go back to false
        // and a later start() must actually retry rather than returning
        // early on `if (synth) return` above.
        synth = null
        sequencer = null
        throw e
      }
    },

    // Hand every event to the sequencer with its own time -- the sequencer
    // holds the timing, not this module.
    schedule (events) {
      if (!synth || !sequencer) return
      const midi = toMidiEvents(events, t => (t - audioContext.currentTime) * 1000)
      for (const m of midi) {
        const event = m.type === 'note-on'
          ? { type: 'note-on', channel: m.chan, key: m.note, vel: m.vel127 }
          : { type: 'note-off', channel: m.chan, key: m.note }
        sequencer.sendEventAt(event, m.atMs, false)
      }
    },

    allNotesOff () {
      if (!synth) return
      // Drop anything already queued in the sequencer -- a gap in the feed
      // can strand a future note-on or note-off there -- then silence
      // every channel immediately.
      if (sequencer) sequencer.removeAllEvents()
      synth.midiAllNotesOff()
    }
  }
}
