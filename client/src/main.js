import { OUTPUT_LATENCY_MS, PING_INTERVAL_MS } from './config.js'
import { makeClock } from './clock.js'
import { makeEditor } from './editor.js'
import { connectionState, hudModel, renderHud } from './hud.js'
import { resetSequence, scheduleCycle } from './schedule.js'
import { connect } from './socket.js'
import { makeSynth } from './synth.js'
import './style.css'

const audio = new AudioContext()
const clock = makeClock()
const synth = makeSynth(audio, { soundfontUrl: '/gm.sf2' })
const hudEl = document.getElementById('hud')
const outEl = document.getElementById('output')
const startBtn = document.getElementById('start')
const latencyEl = document.getElementById('latency')
const latencyValueEl = document.getElementById('latency-value')

let model = null
let pingId = 0
const pending = new Map()

// Set the instant a reconnect's first cycle arrives, and consumed (cleared)
// the moment that cycle is turned into a hud model -- see the contract on
// hudModel in hud.js. Never sticky, never deferred: exactly the next cycle
// after onOpen sees it true.
let justReconnected = false

function pingBurst () {
  // Gate on a running AudioContext: while suspended, audio.currentTime is
  // frozen, so sentAt === receivedAt on every sample and RTT is exactly 0,
  // which always wins the clock's lowest-RTT selection -- anchoring it to a
  // stale reference until the sample ages out of the window. Firing the
  // burst again once the context actually starts (see the click handler)
  // is what makes the very first real clock sync happen on real time.
  if (audio.state !== 'running') return
  for (let i = 0; i < 5; i++) ping()
}

const hud = connect(`ws://${location.host}/hud`, {
  onOpen () {
    // A new connection means a new timeline: forget the sequence, drop
    // anything sounding, and re-sync the clock before scheduling again.
    resetSequence()
    synth.allNotesOff()
    justReconnected = true
    pingBurst()
  },
  onMessage (m) {
    if (m.t === 'pong') {
      const sentAt = pending.get(m.id)
      pending.delete(m.id)
      if (sentAt !== undefined) {
        clock.addSample({ sentAt, receivedAt: audio.currentTime,
                          serverNanos: BigInt(m.s) })
      }
      return
    }
    if (m.t !== 'cycle') return

    const { events, gap } = scheduleCycle(m, clock, audio.currentTime)
    if (gap) synth.allNotesOff()
    synth.schedule(events)

    model = hudModel(m, model, { resumed: justReconnected })
    justReconnected = false
    lastCycleAt = audio.currentTime
    paint()
  },
  onClose () { paint() }
})

let lastCycleAt = null
function paint () {
  if (!model) return
  renderHud(hudEl, model,
            connectionState({ socketOpen: hud.isOpen(),
                              lastCycleAt, now: audio.currentTime }))
}
// Repaint on a slow timer as well as on arrival, so "playing" decays to
// "stopped" when the cycles stop rather than freezing on the last one.
setInterval(paint, 500)

function ping () {
  const id = ++pingId
  pending.set(id, audio.currentTime)
  hud.send({ t: 'ping', id, c: audio.currentTime })
}
// Same gate as the reconnect burst, and for the same reason: a ping sent
// while the context is suspended is a zero-RTT sample, and a ping sent
// while the socket is down leaks a `pending` entry forever.
setInterval(() => { if (audio.state === 'running') ping() }, PING_INTERVAL_MS)

const repl = connect(`ws://${location.host}/repl`, {
  onMessage (m) {
    if (m.t === 'done') return
    outEl.textContent += (m.s ?? '') + (m.t === 'value' ? '\n' : '')
    outEl.scrollTop = outEl.scrollHeight
  }
})

let evalId = 0
const editor = makeEditor(document.getElementById('editor'), {
  doc: ';; C-Enter evaluates the form under the cursor.\n\n(ns jam\n  (:refer-clojure :exclude [rand])\n  (:require [mu.live :refer :all]))\n\n(begin! {:bpm 120})\n\n(def bass (notes c2 _ eb2 g2))\n(play! :bass #\'bass {:chan 0})\n',
  onEval (code) {
    repl.send({ t: 'eval', id: `e${++evalId}`, ns: editor.currentNs(), code })
  }
})

// Browsers refuse audio before a gesture, so the synth starts on a click.
// synth.start() rejects on failure (a missing/unfetchable soundfont is a
// documented normal condition -- client/public/README.md -- not a bug), so
// the button must stay put on failure and the error must be visible, or a
// silent page looks broken instead of merely soundless.
startBtn.addEventListener('click', async () => {
  startBtn.disabled = true
  try {
    await synth.start()
    startBtn.remove()
  } catch (e) {
    outEl.textContent += `start audio failed: ${e.message}\n`
    outEl.scrollTop = outEl.scrollHeight
    startBtn.disabled = false
  }
  // Fire the reconnect burst here too: audio.state only becomes 'running'
  // after this gesture, so the burst that ran (and was skipped) at onOpen
  // needs a second chance now that the clock can actually take real
  // samples. A failed synth.start() still resumes the AudioContext (see
  // synth.js), so this still applies even when the soundfont is missing.
  pingBurst()
})

latencyEl.value = String(OUTPUT_LATENCY_MS)
latencyValueEl.textContent = `${OUTPUT_LATENCY_MS} ms`
latencyEl.addEventListener('input', () => {
  const ms = Number(latencyEl.value)
  clock.setLatency(ms)
  latencyValueEl.textContent = `${ms} ms`
})
