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

All sixteen tasks of the v1 plan are implemented: 74 tests, green. (Two
`mu.midi` tests need a real audio output line and error out on a headless
machine — an environment fact, like `begin!` failing there.) The one
acceptance criterion not met is timing — see [Timing](#timing).

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

**Transforms** — `fast`, `slow`, `early`, `late`, `rev`, `every`, `degrade`,
`degrade-by`, `sometimes`, `sometimes-by`.

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

Seven namespaces in a strict downward dependency chain. `mu.time` and
`mu.pattern` are pure and total, so the whole music algebra is testable with no
clock, no device, and no threads.

| File | Responsibility |
|---|---|
| `src/mu/time.clj` | rational cycle math, spans, cycle splitting |
| `src/mu/pattern.clj` | the `Pattern` record and the query algebra |
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
clojure -M:test -m mu.jitter     # 16ths @120bpm for 60s
```

Measured on the development machine (Termux/PRoot on Android, generational ZGC
confirmed active):

```
n=973  p50=0.176ms  p99=1.588ms  p999=2.234ms  max=2.234ms
```

That is **over budget**. The target has not been weakened. The spike found the
residual tail to be OS scheduling rather than GC — a no-churn control showed the
same p999 as a heavy-GC run — and this host is a pessimistic lower bound.
Re-measure on the target performance machine before concluding anything about
fitness, and see spec section 8 for the native-output gate.

## Known gaps

- **The concatenations do not lift scalars.** `cyc`, `sub`, `stack`, `slowcat`,
  and `fastcat` require Patterns; a raw number throws an NPE at query time. Wrap
  with `pure`: `(sub (pure 0.9) (pure 0.5))`, not `(sub 0.9 0.5)`. The design
  spec's section 5 example `(cyc 0.9 0.5 0.7)` does not work as written.
- **`play!` takes options as a map, not trailing keywords.** Use
  `(play! :bass #'bass {:chan 1})`. The spec's section 7 example
  `(play! #'bass :chan 1)` throws.
- p99 jitter is over budget on the development machine (above).

## Out of scope for v1

Listed so nobody adds them opportunistically: CC/bend/aftertouch automation
(`with` already carries the keys; only `mu.midi/encode` needs cases), scale and
chord libraries, MIDI input and clock sync, explicit transitions and crossfades,
the native timestamped sink (gated on measurement), multi-port routing, and any
GUI.

## Documents

- `docs/repl.md` — the REPL tutorial: starting one, the jam buffer, reading
  patterns without a device
- `docs/superpowers/specs/2026-08-24-mu-design.md` — design spec and rationale
- `docs/superpowers/plans/2026-08-24-mu.md` — the task-by-task implementation plan
