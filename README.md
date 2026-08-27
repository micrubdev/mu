# mu

A Lisp pattern language for live-coding MIDI, hosted on Clojure. Patterns are
query functions, notation is s-expressions, and redefining a pattern lands on
the next cycle boundary.

```clojure
(def bass (notes c2 _ eb2 g2))
(play! :bass #'bass {:chan 0})

(def bass (notes c2 c2 eb2 g2))   ; lands on the next cycle, in time
```

## Status

Complete: the pattern language, the MIDI transport, and the browser web view.

- 171 Clojure tests / 459 assertions, 0 failures
- 68 client tests (Vitest), 0 failures
- p99 dispatch jitter **within the 1 ms budget** on the shipped render path —
  see [Timing](#timing)

Two `mu.midi` tests need a real audio output line and error out on a headless
machine — an environment fact, like `begin!` failing there.

## Requirements

- JDK 21+
- Clojure CLI (1.12)
- No runtime dependencies. `test.check` and `nrepl` are test/dev only.

ZGC is mandatory rather than a preference. Every alias that runs the clock sets
`-XX:+UseZGC -XX:+ZGenerational`; measured under G1 the p99 is 19.7 ms against a
1 ms budget. Running the clock under G1 is a defect, not a slow configuration.

## Quick start

```
clojure -M:live      # nREPL with ZGC and AlwaysPreTouch
```

`docs/repl.md` is the longer version of everything below: editor connection,
the jam-buffer loop, querying patterns with no clock, and working on a machine
with no audio output.

Open a jam buffer:

```clojure
(ns jam
  (:refer-clojure :exclude [rand])
  (:require [mu.live :refer :all]))

(begin! {:port "Gervill" :bpm 120})   ; nil port takes the first available

(def bass (notes c2 _ eb2 g2))
(play! :bass #'bass {:chan 0})

(bpm 140)     ; applies at the next cycle boundary
(hush)        ; drop every voice and silence what is sounding
(end!)        ; stop the transport and close the port
```

`rand` is the only name in `mu.live` that collides with `clojure.core`, hence
the `:refer-clojure :exclude`. (`every?` is core; `every` is not.)

`(mu.midi/list-ports)` shows what this JVM can see. On a machine with no
ALSA or CoreMIDI that is just `Gervill` and `Real Time Sequencer` — an
environment fact, not an error.

## Web view

```
clojure -M:web        # nREPL on 7888, with the web namespaces available
```

```clojure
(web! {:nrepl-port 7888})   ;=> "http://localhost:7890"
```

A browser editor and performance HUD: modal vim over CodeMirror, the live
voice list, the current cycle's events, and the same notes rendered through
a soundfont in the browser. Eval goes to this process's nREPL, so an editor
and the browser can be attached at once.

The server side of this (HTTP, the `/hud` and `/repl` WebSockets, the nREPL
bridge) is what `web!` starts. It serves the browser client from
`client/dist`, built separately — see below.

The web view carries dependencies (http-kit, cheshire, nREPL); mu's core
does not, which is why it lives behind the `:web` alias.

`web!` binds `127.0.0.1` by default; pass `:ip "0.0.0.0"` (or a LAN
address) only if you deliberately want a remote audience view, since it
also exposes this process's nREPL — arbitrary code execution — to
anyone who can reach that interface.

### Building the client

```
cd client && npm install && npm run build
```

Drop a General MIDI soundfont at `client/public/gm.sf2` if you want the
browser to make sound as well as show what is playing; without it the page
is silent and the HUD still works. The browser trails the JVM's own output
by about 80 ms — it is for listening remotely and for the audience view,
not for monitoring in the same room. That lag is a slider ("output
latency") in the page toolbar if 80 ms needs adjusting for your network.

`cd client && npm run dev` serves the page with hot reload and proxies both
sockets to the JVM on 7890.

### Verifying by hand

The client's own test suite (`cd client && npm test`) and an automated
end-to-end check (`node client/scripts/e2e-check.mjs`, needs a live JVM)
cover everything that doesn't need a person watching a screen and
listening. What's left needs eyes, ears, and a real browser:

1. Put a General MIDI soundfont at `client/public/gm.sf2` and rebuild.
2. `clojure -M:web` in one terminal.
3. In that REPL: `(require '[mu.live :refer :all])` then
   `(web! {:nrepl-port 7888})`.
4. Open the printed URL in a browser.
5. Click **start audio**.
6. In the browser editor, put the cursor in `(begin! {:bpm 120})` and press
   `Ctrl-Enter`.

Check each of these:

- [ ] The HUD shows a rising cycle number and `120 bpm`.
- [ ] `Ctrl-Enter` on the `(def bass ...)` and `(play! ...)` forms starts a
      bassline, audible **twice** — once from the JVM's own output, once
      from the browser about 80 ms later.
- [ ] Editing the pattern and re-evaluating changes it on the next cycle
      boundary, in both.
- [ ] `dd` in normal mode deletes a line; `i` enters insert; `:w` is
      harmless.
- [ ] `,e` in normal mode evaluates the form under the cursor, same as
      `Ctrl-Enter`.
- [ ] `(mute :bass)` marks the voice in the HUD.
- [ ] A deliberately broken pattern (`(def bass (notes c2 (/ 1 0)))`) marks
      that voice red in the HUD, keeps playing the last good cycle, and
      prints the error in the output pane.
- [ ] Killing and restarting the JVM makes the page reconnect on its own,
      with no hung notes, and the HUD's dropped count has not increased.

## Notation

`notes` is a macro, so bare symbols are never resolved as vars. A symbol's
*spelling* decides what it is:

| Form | Meaning |
|---|---|
| symbol matching `[a-g](s\|#\|f\|b)*-?[0-9]` | note literal — `c4`, `cs4`, `ef3`, `bb2`, `c-1` |
| `_` | rest |
| any other symbol | left alone; resolves as a var |
| vector | subdivision |
| list | a call, with its arguments rewritten by these same rules |
| number | raw MIDI note number |

Middle C is `c4` = 60. `s` and `#` sharpen, `f` and `b` flatten.

The body is subdivided across one cycle, and a vector subdivides further:

```clojure
(notes c4 _ [eb4 g4])   ; three equal steps; the last splits in two
;; => 60 at [0 1/3), rest, 63 at [2/3 5/6), 67 at [5/6 1)
```

That single spelling rule is why `(cyc bb4 a4)` means two alternating notes
while `(rev riff)` still resolves `riff` — inside the same form, with no
unquote operator.

## The algebra

A pattern is a pure function from a half-open span `[begin end)` to the events
active within it. Time is rational, measured in cycles; a `double` in the
pattern layer is a defect, so `(fast 3 (slow 7 p))` composes exactly instead of
drifting.

An event is `{:whole [b e] :part [b e] :value v}`. It is an **onset** iff
`(= (first whole) (first part))`, and only onsets trigger notes — without that,
a query span bisecting a note would retrigger it mid-sustain.

**Primitives** — `pure`, `silence`, `stack` (parallel), `slowcat`/`cyc` (one per
cycle), `fastcat`/`sub` (all squeezed into one cycle).

**Transforms** — `fast`, `slow`, `early`, `late`, `rev`, `every`, `off`,
`superimpose`, `iter`, `stut`, `arp`, `euclid`, `euclid-full`, `degrade`,
`degrade-by`, `sometimes`, `sometimes-by`. The linear time transforms live in
`mu.pattern`; the rest in `mu.transform`.

**Signals** — `sine`, `saw`, `tri`, `rand`. Continuous, `:whole nil`, so they
never trigger on their own. Change rate with `fast`, not an argument.

```clojure
(fast 2 (notes c4 e4))            ; 60 64 60 64
(every 2 rev (notes c4 d4 e4))    ; reversed on even cycles
(cyc (notes c4) (notes d4))       ; alternating, one per cycle
(degrade (fast 16 (notes c4)))    ; drops about half
```

Randomness is a pure function of time, never a stateful PRNG: querying cycle 400
twice gives the same answer, and reaching it without playing 0–399 gives that
same answer too.

## Merging values with `with`

Structure comes from the left, values from the right.

```clojure
(with (notes c4 d4) :chan 3)     ; scalar, lifted to a constant pattern
(with (notes c4 d4) :cut saw)    ; signal, sampled per note
;; => {:note 60 :cut 0.25}, {:note 62 :cut 0.75}

;; three velocities against four notes: four events, phasing
(with (notes c4 d4 e4 f4) :vel (sub (pure 0.9) (pure 0.5) (pure 0.7)))
;; => vels 0.9 0.9 0.5 0.7
```

Recognised value keys are `:note`, `:vel` (0.0–1.0 or a raw 0–127 integer), and
`:chan`. A per-event `:chan` overrides the voice's channel.

## Scales and chords

Patterns carry integer scale *degrees*; `scale` maps them to MIDI against a
root. Re-rooting or re-moding a whole line is then one edit.

```clojure
(scale :dorian :d3 (notes 0 2 4 [6 4]))
(scale :dorian :f3 (notes 0 2 4 [6 4]))   ; the whole line moves
```

The root is a note-name keyword (`:d3`) or a raw MIDI number. Keywords keep
`scale` an ordinary function, so it composes:

```clojure
(every 4 #(scale :lydian :d3 %) riff)
```

`chord` turns each degree into a diatonic stack, and runs **inside** `scale`,
on degrees, before they become notes:

```clojure
(scale :dorian :d3
  (chord 3 (notes 0 3 4 3)))   ; triads, correct quality per degree, free
```

`(chord 4 ...)` gives sevenths. A chord is one onset, not several: every note
carries the source event's timing.

Modes: the seven church modes (`:ionian`/`:major` through `:locrian`, with
`:minor` = `:aeolian`), plus `:harmonic-minor`, `:melodic-minor`,
`:major-pent`, `:minor-pent`, `:blues`, `:chromatic`. An unknown mode throws,
naming the ones that exist.

`arp` spreads a chord across the slot it occupied, so one onset becomes n:

```clojure
(scale :dorian :d3 (arp :up (chord 3 (notes 0 3 4 3))))
```

Modes are `:up`, `:down`, `:updown`, `:downup`. The turning modes do not repeat
the note they turn on — otherwise a triad limps. Anything without a `:note`,
and any lone event, passes through untouched.

`euclid` distributes k onsets over n steps — E(3,8) is the tresillo — and
`euclid-full` fills the rests instead of resting:

```clojure
(euclid 3 8 (notes c2))              ; x..x..x.
(euclid 3 8 1 (notes c2))            ; rotated left one step
(euclid-full 3 8 (notes c2) (notes d2))   ; c2 on the x, d2 between
```

`off`, `superimpose`, `iter` and `stut` all stack copies against the original:

```clojure
(off 1/8 (partial fast 2) riff)
(superimpose rev riff)
(iter 4 riff)              ; rotates 1/4 further each cycle, home after 4
(stut 3 0.6 1/16 riff)     ; three copies, fading, 1/16 apart
```

`stut` multiplies `:vel`, treating an absent one as `1.0`.

## The live-coding model

Voices hold **vars**, not pattern values, and the render thread derefs every
registered var once per cycle. Deref-per-cycle *is* swap-at-cycle-edge: hot
redefinition lands in time with no transition machinery.

```clojure
(play! #'bass)                      ; key derived from the var name
(play! :bass #'bass {:chan 1})      ; explicit key and options
(stop-voice! :bass)
(mute :bass) (unmute :bass)
(solo :bass) (unsolo)
(panic)                             ; all-notes-off, voices left registered
```

Passing a bare pattern instead of a var registers a snapshot that will not
update.

A voice whose pattern throws — typo, nil, wrong arity — is caught per voice, the
error is printed with the voice's key, and that voice replays its last
successfully rendered cycle. A typo costs you the edit, not the performance.
One failing voice cannot affect its neighbours or the clock.

## Architecture

Nine namespaces in a strict downward dependency chain. `mu.time`,
`mu.pattern`, `mu.transform` and `mu.harmony` are pure and total, so the whole
music algebra is testable with no clock, no device, and no threads.

| File | Responsibility |
|---|---|
| `src/mu/time.clj` | rational cycle math, spans, cycle splitting |
| `src/mu/pattern.clj` | the `Pattern` record and the query algebra |
| `src/mu/transform.clj` | the derived vocabulary built on the algebra |
| `src/mu/harmony.clj` | modes, degrees to MIDI, diatonic stacks |
| `src/mu/notation.clj` | the `notes` macro and note-literal grammar |
| `src/mu/midi.clj` | `MidiSink` protocol, encoding, recording + javax sinks |
| `src/mu/clock.clj` | pure cycle rendering; render + dispatch threads |
| `src/mu/player.clj` | voice registry, var polling, per-voice error isolation |
| `src/mu/live.clj` | performance namespace: re-exports |

Above the pure layer the clock runs two threads:

```
render thread                        dispatch thread (MAX_PRIORITY)
-------------                        ------------------------------
deref each voice's var               walk long[] times, Object[] msgs
query patterns one cycle ahead       parkNanos to ~1.5ms out, then spin
encode into flat sorted arrays       receiver.send(msg, -1)
      \___ ArrayBlockingQueue ______> one handoff per cycle
```

The render thread paces itself against the wall clock, rendering a cycle only
once we are within one cycle length of its start. Backpressure cannot come
from the queue alone: dispatch blocks on message times, so a cycle with no
messages drains instantly, and a stretch of silence — a transport opened
before any voice is registered, a long `hush` — would let the renderer spin at
CPU speed and carry its anchor hours into the future. Nothing registered after
that would ever sound.

The dispatch thread allocates nothing. It walks primitive arrays with an index
cursor, and even the timestamps it hands to `emit!` are pre-boxed by the render
thread — `emit!` takes an `Object`, because Clojure protocol methods cannot be
primitive-hinted, and boxing at the call site would allocate one `Long` per
message on the one thread that must not.

`MidiSink` is deliberately two-phase for the same reason: `encode` runs on the
render thread and may allocate, `emit!` runs on dispatch and may not. This is
also the seam a future timestamped native sink slots into.

A 16 × 128 active-note table makes `hush` and `panic` exact rather than
guesswork, so stuck notes are structurally impossible. Note-offs falling past a
cycle boundary are carried into the next render, so long notes do not hang.

## Testing

```
clojure -X:test
```

The algebra is verified by properties rather than examples. The load-bearing law
is that querying `[a c]` equals querying `[a b]` then `[b c]` for any
cycle-aligned `b` — cycle splitting is where this class of engine actually goes
wrong. `test/mu/pattern_props.clj` holds all seven laws.

The `:test` alias matches both `.*-test$` and `.*-props$`; the runner's default
would silently skip the law suite.

## Timing

Target: **p99 dispatch jitter ≤ 1 ms** (MIDI's own floor is roughly 1 ms, and
jitter becomes audible on percussive attacks around 3–5 ms).

```
clojure -X:test mu.jitter/-main-shipped-tapped   # 16ths @120bpm for 60s
```

Use one of the **shipped** entry points. `-main` and `-main-tapped` call
`clk/start!` bare, which leaves the clock on `default-render-voice` — a path no
real session runs. `player/begin!` installs `safe-render`, and only
`-main-shipped` (and `-main-shipped-tapped`, which additionally attaches a tap
consumer, i.e. a performance with the web view open) measures it.

Note the `-X` form. `clojure -M:web -e ...` does not work: the `:web` alias's
`:main-opts` hijack it into an nREPL banner.

Measured on the development machine (Termux/PRoot on Android, generational ZGC
confirmed active), shipped path with an observer attached:

```
n=973  p50=0.066ms  p99=0.532ms  p999=2.422ms
n=973  p50=0.069ms  p99=0.842ms  p999=3.920ms
n=973  p50=0.071ms  p99=0.851ms  p999=3.223ms
```

**Within budget**, three of three. The tap costs nothing measurable — with a
consumer registered the render thread's added work is one non-blocking `.offer`,
and p99 means with and without a tap are indistinguishable.

This host remains a pessimistic lower bound: it is Termux/PRoot on Android, and
one sample taken under heavy concurrent load did exceed budget (p99 1.167 ms).
Re-measure on the target performance machine, and see spec section 8 for the
native-output gate.

## Known gaps

- **`play!` takes options as a map, not trailing keywords.** Use
  `(play! :bass #'bass {:chan 1})`. The spec's section 7 example
  `(play! #'bass :chan 1)` throws.
- **The browser trails the JVM's own output by ~80 ms.** Deliberate
  (`OUTPUT_LATENCY_MS`, adjustable from the page); it makes the web view a
  remote-listening and audience view, not a same-room monitoring one.
- **`web!` without `:nrepl-port`** serves the HUD but returns 503 on `/repl`,
  so evaluation is unavailable. The page reports this once, on the transition.

## Out of scope for v1

Listed so nobody adds them opportunistically: CC/bend/aftertouch automation
(`with` already carries the keys; only `mu.midi/encode` needs cases), `jux`
(waits on pan), MIDI input and clock sync, explicit transitions and crossfades,
the native timestamped sink (gated on measurement), multi-port routing, and any
GUI.

## Documents

- `docs/api.md` — the API reference: every operator, with worked examples
- `docs/repl.md` — the REPL tutorial: starting one, the jam buffer, reading
  patterns without a device
- `CHANGELOG.md` — what landed, in order
