# Changelog

All notable changes to mu. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

This project has not cut a numbered release yet; everything below is on `main`.

## [Unreleased]

### Tier 1 vocabulary

- `euclid` — k onsets over n steps, with optional rotation. E(3,8) is the
  tresillo.
- `off` and `superimpose` — stack a transformed copy against the original.
- `mu.harmony` — degree-based `scale` over fifteen modes, and scale-relative
  `chord` whose quality falls out of the mode.
- `stack`, `slowcat` and `fastcat` (so `cyc` and `sub`) now lift raw values
  with `pure`, so `(sub 0.9 0.5 0.7)` works. Closes the first Known Gap.

`jux` is deferred until CC support gives it a pan to use.

### Web view — browser client

A browser page with a modal-vim Clojure editor that evaluates into the running
JVM, a live performance HUD, and a soundfont synth rendering the same cycles the
JVM plays. Built from `client/`, served out of `client/dist` by `web!`.

- **`client/src/clock.js`** — maps the server's `System.nanoTime` onto
  `AudioContext.currentTime`. Keeps a reference *pair* rather than a scalar
  offset and subtracts in `BigInt` before dividing, so instants beyond 2^53
  convert exactly. Anchors on the lowest-RTT sample of the last 8.
- **`client/src/schedule.js`** — turns one cycle message into scheduled audio
  events. A cycle that arrives after its own start time is dropped **whole**;
  never flushed as a burst.
- **`client/src/socket.js`** — reconnecting JSON WebSocket, 250 ms backoff
  doubling to a 4 s cap. `WebSocketImpl`/`setTimeoutImpl` are injected so the
  reconnect state machine is testable with no network and no wall clock.
- **`client/src/synth.js`** — js-synthesizer (FluidSynth/WASM) in an
  AudioWorklet, driven by its sequencer's timestamped events. Nothing runs on a
  JS timer, so a backgrounded tab cannot stutter the audio.
- **`client/src/editor.js`** — CodeMirror 6 with `@replit/codemirror-vim` and
  Clojure syntax. `Ctrl-Enter` evaluates the top-level form under the cursor,
  `Ctrl-Shift-Enter` the whole buffer, `,e` in normal mode. `topLevelFormAt` is a
  reader-aware scan: strings, character literals, comments and reader prefixes
  all handled.
- **`client/src/hud.js`** — pure view model over cycle messages. Three
  connection states, not two: an open socket gone quiet means the transport
  stopped, not a lost connection.
- **`client/src/main.js`** — wires the modules to the two sockets.
- Output latency defaults to 80 ms and is adjustable from the page.
- `client/scripts/e2e-check.mjs` — end-to-end check against a live JVM: real
  WebSockets, real server frames, fed through the client's own modules.

### Web view — server

- **`mu.tap`** — drop-on-full fan-out for cycle observers. Offers, never puts,
  so nothing the web view does can reach the render thread.
- **`mu.web.protocol`** — the wire format. Absolute instants travel as decimal
  strings (`JSON.parse` would silently round `System.nanoTime` past 2^53), and
  each message carries its offset from the cycle anchor.
- **`mu.web.repl`** — nREPL bridge, one session per browser tab.
- **`mu.web.server`** — HTTP plus `/hud` (one message per rendered cycle, and
  ping/pong clock sync) and `/repl`. Binds `127.0.0.1` by default.
- **`(web!)` / `(web-off!)`** in `mu.live`, behind the `:web` alias so mu's core
  keeps its zero runtime dependencies.
- `mu.player` gained an `!errors` atom, so a voice that throws rides the
  `voices` snapshot as `:error` and the HUD can mark it.
- `clock/start!` gained an injectable `:render-voice` seam; `player/begin!`
  passes `safe-render`, which previously never ran on the live path — a throwing
  pattern used to kill the render thread silently.

### Timing

- p99 dispatch jitter is **within the 1 ms budget** on the shipped render path
  (0.532 / 0.842 / 0.851 ms, three of three), measured with a tap consumer
  attached. Previously recorded as over budget, but that figure came from
  `-main`, which measures `default-render-voice` — a path no real session runs.
- `mu.jitter` gained `-main-shipped` and `-main-shipped-tapped`, which measure
  the configuration `player/begin!` actually starts.

### Fixed

- `web!` now defaults to `127.0.0.1`. It previously bound `0.0.0.0` while every
  user-facing string promised localhost, which made `/repl` an unauthenticated
  arbitrary-code-execution endpoint for anyone on the same network.
- A failed nREPL connect no longer poisons a `/repl` session for the life of the
  process.
- `socket.js` no longer reconnects after an explicit `close()` issued during the
  backoff window.
- `topLevelFormAt` no longer drops reader prefixes: `#_(...)` — the idiomatic way
  to disable a form — was evaluating the code it was meant to discard, and
  `#{1 2 3}` evaluated as invalid `{1 2 3}`. A stray unbalanced `)` no longer
  kills evaluation for the rest of the buffer or evaluates an inner subform as
  though it were top-level.
- `currentNs()` is reader-aware; an `(ns ...)` inside a docstring or comment no
  longer redirects every evaluation to the wrong namespace.
- `synth.start()` checks the soundfont response, resets its state on failure so a
  retry actually retries, and rethrows. It previously reported ready on a
  half-initialised synth and silently dropped every event.
- `connectionState` treats `undefined` like `null`, instead of falling through to
  the most reassuring state.
- The HUD renders its transport line before the first cycle arrives, and decays
  to `stopped` for a viewer who never starts audio (`AudioContext.currentTime` is
  frozen while suspended, so the decay logic uses `performance.now()`).
- A locally dropped cycle now triggers all-notes-off; a carried note-off inside
  it could otherwise leave a note sounding forever.
- The clock resets on reconnect, so a stale pre-disconnect sample cannot anchor
  the new timeline.
- `vite.config.js` no longer empties `client/dist` on build, which was deleting
  the tracked `.gitkeep`.

## Earlier

The pattern language and MIDI transport: pure query-function patterns with exact
rational time, the `notes` macro and note-literal grammar, the pattern algebra
(`stack`, `slowcat`/`cyc`, `fastcat`/`sub`, `slow`, `early`/`late`, `rev`,
`every`, `degrade`, `sometimes`, continuous signals, `with`), a `MidiSink`
protocol with a `javax` implementation, render and dispatch threads with exact
panic, a voice registry with per-voice error isolation, and the jitter acceptance
harness. See `docs/superpowers/specs/2026-08-24-mu-design.md`.
