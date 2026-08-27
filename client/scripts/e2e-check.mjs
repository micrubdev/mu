#!/usr/bin/env node
// Real end-to-end check, driven from Node against a live JVM.
//
// Not a vitest test: it needs a live JVM and must not run as part of the
// unit suite (`npm test`). Run it directly:
//
//   cd client && npm run build && node scripts/e2e-check.mjs
//
// It starts the actual server (`mu.web/web!`, the same call a performer
// makes), drives it exactly as a browser would -- HTTP for the built
// assets, WebSocket frames for /hud and /repl -- and then, the part a
// hand-written fixture can never catch, feeds the server's *real* wire
// output through the client's own pure modules (clock.js, schedule.js,
// hud.js) to prove the wire format the server actually emits is the wire
// format the client actually expects.
//
// Requires `clojure` on PATH and client/dist already built.

import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

import { makeClock } from '../src/clock.js'
import { resetSequence, scheduleCycle } from '../src/schedule.js'
import { hudModel } from '../src/hud.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(__dirname, '..', '..')

const READY_TIMEOUT_MS = 120_000  // cold classpath resolution can be slow
const CYCLE_TIMEOUT_MS = 15_000   // 120bpm = 2s/cycle; wait for a couple

let failed = false
function check (name, cond, detail) {
  if (cond) {
    console.log(`  ok - ${name}`)
  } else {
    failed = true
    console.log(`  FAIL - ${name}${detail !== undefined ? ': ' + detail : ''}`)
  }
}

const nowSec = () => Date.now() / 1000

function withTimeout (promise, ms, label) {
  let t
  const timeout = new Promise((_, reject) => {
    t = setTimeout(() => reject(new Error(`timed out waiting for: ${label}`)), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(t))
}

// --- 1. Start the JVM -------------------------------------------------
//
// `clojure -M:web`'s own main-opts run nrepl.cmdline, an interactive
// text REPL over stdio -- awkward to drive reliably from a child
// process (verified: an `-e` appended after it is silently swallowed as
// an unrecognised arg to nrepl.cmdline, not evaluated). Instead this
// defines an alias with -Sdeps that mirrors :web's extra-paths/
// extra-deps from deps.edn but carries no main-opts of its own, so a
// plain `-e` launcher runs directly: start a real nREPL server, then
// call mu.web/web! pointed at it -- exactly what a performer's own
// `(web! {:nrepl-port ...})` does.
const E2E_ALIAS_EDN =
  '{:aliases {:e2eweb {:extra-paths ["web" "dev"] ' +
  ':extra-deps {http-kit/http-kit {:mvn/version "2.8.0"} ' +
  'cheshire/cheshire {:mvn/version "5.13.0"} ' +
  'nrepl/nrepl {:mvn/version "1.3.0"}}}}}'

const LAUNCHER_CLJ = `
(require '[mu.web :as web] '[nrepl.server :as nrepl-server])
(let [nserv (nrepl-server/start-server :port 0)
      url   (web/web! {:port 0 :nrepl-port (:port nserv)})]
  (println (str "E2E-READY " url " " (:port nserv)))
  (flush))
(while true (Thread/sleep 1000))
`

function startJvm () {
  const child = spawn('clojure',
    ['-Sdeps', E2E_ALIAS_EDN, '-M:e2eweb', '-e', LAUNCHER_CLJ],
    { cwd: REPO_ROOT })

  let stdout = ''
  let stderr = ''
  child.stdout.on('data', d => { stdout += d })
  child.stderr.on('data', d => { stderr += d })

  const ready = new Promise((resolve, reject) => {
    child.stdout.on('data', () => {
      const m = stdout.match(/E2E-READY (\S+) (\d+)/)
      if (m) resolve({ url: m[1], nreplPort: Number(m[2]) })
    })
    child.on('exit', (code, signal) => {
      reject(new Error(`clojure process exited before ready (code=${code} signal=${signal})\n--- stdout ---\n${stdout}\n--- stderr ---\n${stderr}`))
    })
  })

  return { child, ready: withTimeout(ready, READY_TIMEOUT_MS, 'JVM ready'), getOutput: () => ({ stdout, stderr }) }
}

function waitForOpen (ws) {
  return new Promise((resolve, reject) => {
    ws.addEventListener('open', () => resolve(ws), { once: true })
    ws.addEventListener('error', e => reject(new Error(`websocket error: ${e.message ?? e}`)), { once: true })
  })
}

function waitForFrame (ws, pred, label, frames) {
  return withTimeout(new Promise((resolve, reject) => {
    const onMessage = ev => {
      let m
      try { m = JSON.parse(ev.data) } catch { return }
      frames?.push(m)
      if (pred(m)) { ws.removeEventListener('message', onMessage); resolve(m) }
    }
    ws.addEventListener('message', onMessage)
    ws.addEventListener('error', e => reject(new Error(`websocket error: ${e.message ?? e}`)), { once: true })
  }), 10_000, label)
}

async function main () {
  console.log('Starting JVM (clojure -M:web equivalent)...')
  const { child, ready, getOutput } = startJvm()

  try {
    const { url, nreplPort } = await ready
    console.log(`JVM ready: ${url}  (nrepl on ${nreplPort})`)
    const wsBase = url.replace(/^http/, 'ws')

    // --- 2. GET / ---------------------------------------------------
    const indexRes = await fetch(url + '/')
    const indexHtml = await indexRes.text()
    check('GET / returns 200', indexRes.status === 200, indexRes.status)
    check('GET / content-type is text/html',
      (indexRes.headers.get('content-type') ?? '').includes('text/html'),
      indexRes.headers.get('content-type'))

    // --- 3. GET the built JS asset -----------------------------------
    const jsMatch = indexHtml.match(/src="([^"]+\.js)"/)
    check('index.html references a built .js asset', Boolean(jsMatch), indexHtml)
    if (jsMatch) {
      const jsRes = await fetch(url + jsMatch[1])
      check(`GET ${jsMatch[1]} returns 200`, jsRes.status === 200, jsRes.status)
      check(`GET ${jsMatch[1]} content-type is text/javascript`,
        (jsRes.headers.get('content-type') ?? '').includes('text/javascript'),
        jsRes.headers.get('content-type'))
    }

    // --- 4. /hud: ping/pong -------------------------------------------
    const hudFrames = []
    const hud = new WebSocket(wsBase + '/hud')
    await waitForOpen(hud)

    const sentAt = nowSec()
    hud.send(JSON.stringify({ t: 'ping', id: 1, c: sentAt }))
    const pong = await waitForFrame(hud, m => m.t === 'pong' && m.id === 1, 'pong', hudFrames)
    const receivedAt = nowSec()
    check('pong.s is a decimal string', typeof pong.s === 'string' && /^\d+$/.test(pong.s), pong.s)
    let serverNanos = null
    try { serverNanos = BigInt(pong.s) } catch { /* checked below */ }
    check('pong.s parses as BigInt', serverNanos !== null, pong.s)

    // --- 5. /repl: eval real code, over the real bridge ----------------
    const repl = new WebSocket(wsBase + '/repl')
    await waitForOpen(repl)
    const replFrames = []

    repl.addEventListener('message', ev => {
      let m
      try { m = JSON.parse(ev.data) } catch { return }
      replFrames.push(m)
    })

    // :port "sequencer" picks the JDK's "Real Time Sequencer" rather than
    // the default "Gervill" software synth -- Gervill's receiver opens a
    // real audio line, which this (and any headless CI) container has
    // none of. That failure is the same environmental one documented for
    // `clojure -X:test`'s two known headless MIDI errors, not something
    // this check exists to catch, so it is worked around the same way a
    // real headless deploy would: pick the port that does not need audio
    // hardware.
    repl.send(JSON.stringify({
      t: 'eval', id: 'e1', ns: 'user',
      code: "(ns jam (:refer-clojure :exclude [rand]) (:require [mu.live :refer :all])) (begin! {:bpm 120 :port \"sequencer\"})"
    }))
    await waitForFrame(repl, m => m.t === 'done' && m.id === 'e1', 'eval e1 done')
    const e1Err = replFrames.find(m => m.id === 'e1' && (m.t === 'err' || m.t === 'ex'))
    check('eval e1 (ns + begin!) produced no error', !e1Err, e1Err?.s)

    repl.send(JSON.stringify({
      t: 'eval', id: 'e2', ns: 'jam',
      code: "(def bass (notes c2 _ eb2 g2)) (play! :bass #'bass {:chan 0})"
    }))
    await waitForFrame(repl, m => m.t === 'done' && m.id === 'e2', 'eval e2 done')
    const e2Err = replFrames.find(m => m.id === 'e2' && (m.t === 'err' || m.t === 'ex'))
    check('eval e2 (def bass + play!) produced no error', !e2Err, e2Err?.s)

    // --- 6. /hud: real cycle messages ----------------------------------
    const cycles = []
    while (cycles.length < 2) {
      const m = await waitForFrame(hud, m => m.t === 'cycle', 'cycle message', hudFrames)
      cycles.push(m)
    }
    const cycleReceivedAt = nowSec()
    check('cycle numbers are ascending', cycles[1].n > cycles[0].n,
      `${cycles[0].n} then ${cycles[1].n}`)
    check('cycle bpm is 120', cycles[0].bpm === 120, cycles[0].bpm)
    check("cycle voices include 'bass'", Boolean(cycles[0].voices?.bass),
      JSON.stringify(cycles[0].voices))
    check('cycle carries note messages', (cycles[0].msgs?.length ?? 0) > 0,
      cycles[0].msgs?.length)

    hud.close()
    repl.close()

    // --- 7. Feed the real frames through the client's own pure modules -
    //
    // The single most valuable assertion in this whole script: real
    // server output, run through the real client code that will consume
    // it in the browser, not a hand-written fixture.
    console.log('Feeding live server frames through client/src/clock.js, schedule.js, hud.js...')

    const clock = makeClock()
    if (serverNanos !== null) {
      clock.addSample({ sentAt, receivedAt, serverNanos })
    }
    check('clock.ready() after a real pong', clock.ready())

    resetSequence()
    const sched1 = scheduleCycle(cycles[0], clock, cycleReceivedAt)
    const sched2 = scheduleCycle(cycles[1], clock, cycleReceivedAt)

    check('scheduleCycle on a real cycle message does not throw and returns events',
      Array.isArray(sched1.events))
    if (!sched1.dropped) {
      const times = sched1.events.map(e => e.time)
      const ascending = times.every((t, i) => i === 0 || t >= times[i - 1])
      check('scheduled event times are ascending', ascending, times)
    } else {
      check('first real cycle was not dropped', false,
        'clock/timing mismatch between the harness\'s wall-clock stand-in and the server -- see report')
    }
    check('the second consecutive real cycle is not flagged as a gap', !sched2.gap, sched2.gap)

    const model1 = hudModel(cycles[0], null)
    check('hudModel(real cycle) reports bpm 120', model1.bpm === 120, model1.bpm)
    check("hudModel(real cycle) lists voice 'bass'",
      model1.voices.some(v => v.name === 'bass'), JSON.stringify(model1.voices))
    check('hudModel(real cycle) produced note-on events', model1.events.length > 0,
      model1.events.length)

  } finally {
    // --- 8. Shut the JVM down cleanly ----------------------------------
    child.kill('SIGTERM')
    await new Promise(resolve => {
      const t = setTimeout(() => { child.kill('SIGKILL'); resolve() }, 5000)
      child.on('exit', () => { clearTimeout(t); resolve() })
    })
  }
}

main()
  .then(() => {
    console.log(failed ? '\nRESULT: FAIL' : '\nRESULT: PASS')
    process.exit(failed ? 1 : 0)
  })
  .catch(e => {
    console.error('\nRESULT: ERROR')
    console.error(e)
    process.exit(1)
  })
