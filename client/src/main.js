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

// wss:// behind any TLS terminator, plain ws:// otherwise -- a page served
// over https would otherwise have both sockets blocked by mixed-content.
const wsScheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
const wsUrl = path => `${wsScheme}//${location.host}${path}`

// The output pane grows for the life of the page; cap it so a long session
// does not turn it into an unbounded string.
const OUT_MAX_CHARS = 100_000
function appendOut (text) {
  outEl.textContent += text
  if (outEl.textContent.length > OUT_MAX_CHARS) {
    outEl.textContent = outEl.textContent.slice(-OUT_MAX_CHARS)
  }
  outEl.scrollTop = outEl.scrollHeight
}

// Seeded rather than null: paint() must always have something to render,
// or the HUD stays completely empty until the first cycle ever arrives --
// including the whole time the server is down, or the transport is
// stopped, which is exactly when a transport line matters most.
let model = { cycle: '--', bpm: null, drops: 0, voices: [], events: [] }
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

// lastCycleAt/now for the HUD's decay-to-"stopped" logic use
// performance.now(), not audio.currentTime: audio.currentTime is frozen
// at 0 while the AudioContext is suspended, which is the normal state for
// a viewer who never clicks "start audio" (HUD-only operation is a
// documented supported mode -- client/public/README.md). With a frozen
// clock, connectionState's quiet-window check never fires and the HUD
// reports "playing" forever. audio.currentTime stays the time base for
// everything the clock and scheduler touch below; only this UI decay
// logic uses wall-clock time, and the two must never mix.
let lastCycleAt = null

const hud = connect(wsUrl('/hud'), {
  onOpen () {
    // A new connection means a new timeline: forget the sequence, drop
    // anything sounding, re-sync the clock before scheduling again, and
    // drop any ping still awaiting a pong from before the reconnect.
    resetSequence()
    clock.reset()
    synth.allNotesOff()
    pending.clear()
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

    const { events, gap, dropped } = scheduleCycle(m, clock, audio.currentTime)
    // A gap means the server-side sequence broke; a local drop means this
    // cycle's own note-on already reached the sequencer while its note-off
    // (carried into the next cycle's messages by src/mu/clock.clj) did
    // not, because that next cycle arrived late and got dropped whole.
    // Either way, silence what's already sounding rather than risk a hung
    // note.
    if (gap || dropped) synth.allNotesOff()
    synth.schedule(events)

    model = hudModel(m, model, { resumed: justReconnected })
    justReconnected = false
    lastCycleAt = performance.now() / 1000
    paint()
  },
  onClose () { paint() }
})

function paint () {
  renderHud(hudEl, model,
            connectionState({ socketOpen: hud.isOpen(),
                              lastCycleAt, now: performance.now() / 1000 }))
}
// Repaint on a slow timer as well as on arrival, so "playing" decays to
// "stopped" when the cycles stop rather than freezing on the last one.
setInterval(paint, 500)

function ping () {
  // Guard against leaking a `pending` entry while the socket is down:
  // hud.send() is a silent no-op on a closed socket (socket.js), so a
  // ping sent while disconnected would add an entry no pong can ever
  // arrive to clear -- one per interval tick for the whole disconnect,
  // plus five per reconnect burst, never pruned.
  if (!hud.isOpen()) return
  const id = ++pingId
  pending.set(id, audio.currentTime)
  hud.send({ t: 'ping', id, c: audio.currentTime })
}
// Gate on a running AudioContext, separately from the isOpen() gate inside
// ping(): while suspended, audio.currentTime is frozen, so sentAt ===
// receivedAt on every sample and RTT is exactly 0, which always wins the
// clock's lowest-RTT selection.
setInterval(() => { if (audio.state === 'running') ping() }, PING_INTERVAL_MS)

// Latched, so the pane reports each TRANSITION once rather than each attempt.
// socket.js retries on a backoff capped at RECONNECT_MAX_MS, so an unlatched
// onClose writes a line every 4 s forever on the no-:nrepl-port path -- which
// is precisely the path where the message is the only useful thing the pane
// has to say, and where burying it under copies of itself is worst.
// null = nothing reported yet, so the first event of either kind prints.
let replUp = null

const repl = connect(wsUrl('/repl'), {
  // web/mu/web/server.clj returns 503 for /repl when :nrepl-port is nil --
  // and web!'s own docstring documents calling it without :nrepl-port as
  // normal. Without these, that upgrade fails, socket.js reconnect-loops
  // silently, and Ctrl-Enter becomes a no-op with no visible cause.
  onOpen () {
    if (replUp !== true) appendOut('repl connected\n')
    replUp = true
  },
  onClose () {
    if (replUp !== false) appendOut('repl disconnected — is :nrepl-port set?\n')
    replUp = false
  },
  onMessage (m) {
    if (m.t === 'done') return
    appendOut((m.s ?? '') + (m.t === 'value' ? '\n' : ''))
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
    appendOut(`start audio failed: ${e.message}\n`)
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
