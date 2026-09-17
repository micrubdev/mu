# Changelog

All notable changes to mu. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

This project has not cut a numbered release yet; everything below is on `main`.

## [Unreleased]

### Textbeat vocabulary (2026-09-17)

Six borrowings from [textbeat](https://github.com/flipcoder/textbeat),
Grady O'Connell's plaintext tracker, each mapped onto the pure-pattern
model rather than copied. The constraint throughout: patterns stay pure
query functions and every change lands on a cycle boundary, so
textbeat's stateful features (persistent `@v` volume, "walk until
muted") are re-expressed as pattern data. Seven commits, `8ef6331` to
`c01a0e8`; 17 files, +1253/−91; 279 tests / 1121 assertions, up from
233 / 1014. Design note in `docs/superpowers/specs/2026-09-17-textbeat-vocabulary-design.md`
(local; that directory is gitignored).

#### Added

- **Articulation suffixes** (`mu.notation`). A note name or drum keyword
  carries expression where it is written, in any order, each at most
  once:
  - `!` → `:vel 1.0`; `!<digits>` → `:vel 0.<digits>` for one to three
    digits (`!75` 0.75, `!5` 0.5, `!333` 0.333); `!!` and `!100` are
    1.0.
  - `?` → `:vel 0.4`.
  - `*` → `:mod 1.0`, the modulation wheel for the note's duration.
  - `>` → `:legato true`.
  - `split-suffix` is public: `[stem suffix-map]` from a literal's name,
    shared by `notes`, `deg` and `mu.score`.
- **Legato in the render** (`mu.render/voice-messages`). A `:legato`
  onset's note-off moves to the start of the voice's next onset — any
  note, any channel of that voice — or, with nothing after it, to the
  cycle end. Other voices never cut a legato note. A note-off exactly at
  the boundary belongs to the current cycle, as before, so nothing new
  is carried.
- **`deg`** (`mu.notation`, macro). Scale degrees counted from one:
  `(deg 1 b3 5 8)`. Events carry `{:note d :deg true}` with `d`
  zero-based, plus `:alter` when the literal was `b`- or `s`-prefixed
  (`bb7` is −2). Numbers keep counting past seven (`8` is the octave)
  and go negative below the root (`-1` is the leading degree); `0`
  throws. `n` prefixes a natural that needs a suffix — `n5!`. Rests,
  vectors, nested calls, keywords-as-drums and articulation suffixes
  behave as in `notes`. `literal`, `degree-literal` and
  `degree->zero-based` are public for `mu.score`.
- **`key`** (`mu.harmony`). `(key root mode p)` — `scale` in textbeat's
  argument order, with an octaveless root defaulting to octave 3
  (`:d` → `:d3`; `:d4` is taken as written).
- **`strum`** (`mu.transform`). `(strum spread p)`: note *i* of an
  *n*-note stack starts `i·spread/n` into the shared whole and holds to
  its end; negative spread strums high to low; a lone note passes
  through. textbeat's `maj$_`.
- **`walk`** (`mu.transform`). `(walk n p)`: on cycle *c* each stack
  sounds its `(c mod n)`-th note, wrapping past the stack size, keeping
  the stack's own whole. `n <= 0` is the identity. textbeat's `maj&4`.
- **Control events on the render path** (`mu.render`). Onset values
  without `:note` now render: `{:cc n :val v}` → one control change,
  `{:bend v}` → one pitch bend (−1.0–1.0), `{:mod v}` → cc 1. None has a
  note-off. A note carrying `:mod` sends cc 1 = `:mod` immediately
  before its note-on and cc 1 = 0 immediately after its note-off, in
  that list order at equal instants.
- **`ctrl`, `bend`, `modw`** (`mu.transform`). Sample a signal `rate`
  times per cycle into those control events: `(ctrl 74 16 (slow 4
  sine))`. `modw` rather than `mod`, which is core's modulo.
- **`:bend` encoding** (`mu.midi`). `bend->midi` maps −1.0–1.0 onto the
  14-bit range asymmetrically — 8192 steps down, 8191 up — so both
  extremes are reachable exactly; `encode` splits it LSB-first into a
  `PITCH_BEND` message.
- **`song`, `song-length`, `section-at`, `once`** (`mu.transform`).
  `(song [:a 8 pa] [:b 4 pb])` plays sections for their cycle counts and
  loops; each section sees its own cycle count from zero (the
  `slowcat` offset argument), so `every` inside a section counts
  section cycles; sections are patterns, so songs nest. The section
  table lives in the pattern's metadata; `section-at` returns
  `[label cycle-in-section]`. Bad sections (zero cycles, wrong shape, no
  sections) throw at construction. `once` plays cycle 0 only.
- **`mu.score`** — the `.mu` score. A plaintext file where columns are
  voices and rows are steps:
  - Directives: `%bpm n`, `%grid n` (rows per cycle, default 4), `%key
    root mode`, `%chan track n`; several per line. `;` starts a comment.
  - The first remaining line is the header; its word positions define
    the columns. A cell belongs to the nearest column start at or left
    of its first character. `[a b]` is one token.
  - A cell is one literal — `notes` notation, or `deg` under `%key` —
    with suffixes; `_` or an empty cell is a rest; `[a b]` subdivides.
    Cells use their own small grammar, not the Clojure reader, so `8>`
    and `1!` are legal there.
  - A blank line ends a section. `@name` labels the next block, `@name
    x3` repeats it, a bare `@name` replays a section written earlier;
    unlabelled sections are numbered. Tracks become `song`s; a short
    final cycle is padded with rests.
  - `parse` and `compile` are pure. `load!` registers one voice per
    track under its name (`%chan` honoured), sets `bpm`, and on reload
    replaces the voices and stops any the score dropped. `watch!` polls
    mtime once a second and reloads, keeping the last good score on a
    parse error; `unwatch!` stops it.
- **Re-exports** in `mu.live`: `deg`, `key`, `strum`, `walk`, `ctrl`,
  `bend`, `modw`, `song`, `section-at`, `song-length`, `once`, `load!`,
  `watch!`, `unwatch!`.

#### Changed

- `mu.harmony/scale` applies an event's `:alter` on top of the mode's
  own degree and adjusts the carried spelling (`b3` in D major is F
  natural, not E), then drops `:alter` and `:deg` from the value.
- `mu.transform/arp` is now written over a shared `restack` helper
  (cycle-anchored stack grouping with a total sort), which `strum` and
  `walk` reuse; behaviour is unchanged.
- `mu.midi/encode` reads a `:cc` value by the velocity rule: a 0.0–1.0
  double scales, an integer is the raw byte.
- `mu.live` excludes `key` from `clojure.core` alongside `rand`; the jam
  buffer in the README and `docs/repl.md` is now
  `(:refer-clojure :exclude [rand key])`.
- README: articulation and `deg`/`key` under Notation, a new "The `.mu`
  score" subsection, CC/bend automation removed from "Out of scope".
  `docs/api.md`: Articulation, Degrees, Automation, Song form and Score
  sections, `strum`/`walk` under Harmony, index updated.

#### Notation decisions forced by the reader

- Vibrato is `*`, not textbeat's `~`: `~` is unquote.
- Sharps are `s4`, not `#4`: `#` is the dispatch character.
- Tokens that start with a digit cannot carry a suffix or mark as
  Clojure symbols (`1'`, `36!`, `5!` are invalid numbers), so: raw MIDI
  numbers take no suffix; `deg` has the `n` prefix and writes octaves as
  numbers (`8`, `-1`) rather than `'`/`,`; `.mu` cells bypass the reader.
- Inside `notes`, `b3` remains the note B3. Flat degrees exist only in
  `deg` and in `%key` scores.

#### Dropped from textbeat, deliberately

- Duration suffixes (`*`, `.`): the whole already says it — `[c4 _]`.
- Named chord vocabulary (`maj7#4/C`): `chord` is diatonic by design.
- textbeat's stateful volume (`@v`) and "walk until muted": the pattern
  loops by default and `with` sets velocity.

### Program change

- `mu.midi/encode` gained `:program`. It is a two-byte message — status
  and the program number, no second data byte — which is why the
  encoding test asserts a length of two.
- `mu.midi/send-now!` encodes and emits in one step, for messages with
  no schedule to sit on. The render path still encodes ahead of time so
  the dispatch thread never allocates.
- `program!` and `cc!` in `mu.player`, re-exported from `mu.live`. `:cc`
  had been reachable only by `mu.clock`'s all-notes-off panic since it
  was written; this is the first way to send one by hand.
- Deliberately not pattern data. A patch is a mode a channel is in, not
  an event: carrying it per-event would mean a last-program-per-channel
  table on a render path that is pure by design. Both throw outside
  0–127 rather than clamping, because a clamped patch number is a typo
  you never find.

### Demo

- `dev/mu/demo.clj` and the `:demo` alias — a one-shot piece that plays
  itself through seven sections and stops, exercising the vocabulary in
  one readable place. It sets its own patches, so it sounds as written
  on any General MIDI device.

### Pitch spelling

- `mu.pitch` — the spell shape (`{:step :e :alter -1 :octave 3}`), `spelled`,
  and `transpose` by named interval. Semitone transposition cannot preserve a
  spelling; a named interval carries the letter-step count that can.
- `notes` literals now carry the spelling they were written with; `scale`
  derives one by advancing the root's letter. `:blues`, `:chromatic` and a raw
  MIDI root spell nothing rather than guessing — a wrong glyph is worse than a
  plain one.
- `:note` is unchanged and still canonical. Spelling is advisory and verified
  by `spelled`, the only reader.

Phase 1 of the composition roadmap: the information a score needs, produced
but not yet consumed.

### Namespace splits

`mu.clock` was 329 lines against the ~300 constraint, so the pure half moved
to `mu.render` along the seam the file already marked (`;; ---- transport`).
`render-cycle` and friends are thread-free and testable with no transport;
`mu.clock` is now just the two threads. `lsys` likewise moved to
`mu.grammar`. No behaviour change: same 180 tests, and the shipped jitter
benchmark measures p99 0.779 ms, inside the 1 ms budget.

### Grammars

- `mu.grammar` — a home for operators that MAKE a pattern from a rule,
  rather than reshaping one you already have. `mu.transform` was at 295 lines
  against the ~300 constraint; this is the seam cellular automata and pure
  Markov walks would slot into.
- `lsys` — Lindenmayer systems. Rules map a symbol to a vector of symbols; a
  symbol with no rule is a constant. Numeric symbols become `{:note n}`, so an
  L-system over integers composes directly with `scale`. Expansion throws past
  4096 symbols rather than wedging the render thread on a cycle with thousands
  of events.

### The arp batch

- `arp` — spread a chord across the span it occupied. `:up`, `:down`,
  `:updown`, `:downup`.
- `iter` — rotate a further 1/n each cycle, home after n.
- `stut` — n copies, fading by a feedback factor.
- All four are under the seven laws in `test/mu/pattern_props.clj`.
- `euclid-full` — play a second pattern on the rests.

`mu.pattern` was at 299 lines against the ~300 constraint, so the derived
vocabulary (`every`, `off`, `superimpose`, `euclid`, the degrade family) moved
to a new `mu.transform`. `mu.pattern` is now the algebra; `mu.transform` is
what is built on it. `time-rand` became public as the shared seed. No
behaviour change.

Known: `late` and `early` are not span-canonical -- a shifted event's `:part`
is one piece when queried whole and two when queried per cycle. Onsets and
wholes are identical either way, so nothing sounds different, and the clock
only triggers onsets.

This cannot be fixed inside `late`. Splitting its results, or splitting its
incoming span, both satisfy the splitting law but break
`early-is-the-inverse-of-late`: the second shift moves the first one's
fragment boundaries off the integers, leaving two fragments of one whole
inside a single cycle. Combinators built on `late` instead anchor on the
containing cycle, as `rev` and `every` always have -- see `stut` and `arp`.

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
harness.
