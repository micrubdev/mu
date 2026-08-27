# mu API reference

Every example below was run against the code in this repo, and the `;;=>`
lines are its actual output.

Open a jam buffer first:

```clojure
(ns jam
  (:refer-clojure :exclude [rand])
  (:require [mu.live :refer :all]))
```

`rand` is the only name in `mu.live` that collides with `clojure.core`, hence
the exclude. (`every?` is core; `every` is not.)

## Contents

- [The model](#the-model) — what a pattern *is*
- [Notation](#notation) — `notes`, `note-name->midi`
- [Primitives](#primitives) — `pure`, `silence`, `stack`, `cyc`, `sub`
- [Time](#time) — `fast`, `slow`, `early`, `late`, `rev`
- [Structure](#structure) — `every`, `iter`, `off`, `superimpose`, `stut`
- [Rhythm](#rhythm) — `euclid`, `euclid-full`
- [Grammars](#grammars) — `lsys`
- [Randomness](#randomness) — `degrade`, `sometimes`
- [Signals](#signals) — `sine`, `saw`, `tri`, `rand`
- [Values](#values) — `fmap`, `with`
- [Harmony](#harmony) — `scale`, `chord`, `arp`
- [Performance](#performance) — `play!`, `mute`, `begin!`, transport
- [Web view](#web-view) — `web!`
- [Index](#index)

## The model

A pattern is a pure function from a half-open span `[begin end)` to the events
active in it. Nothing is stateful, so you can ask any pattern about any point
in time without playing up to it.

```clojure
(query (notes c4 e4) [0 1])
;;=> ({:whole [0 1/2], :part [0 1/2], :value {:note 60}}
;;    {:whole [1/2 1], :part [1/2 1], :value {:note 64}})
```

An event is `{:whole [b e] :part [b e] :value v}`.

- `:whole` is the note's full extent — when it starts and ends.
- `:part` is the slice of it visible in *this* query.
- An event is an **onset** iff `(= (first :whole) (first :part))`.

**Only onsets trigger notes.** A query that bisects a sustaining note yields a
fragment whose part starts after its whole; without the onset rule that note
would retrigger mid-sustain.

Time is rational — `1/3`, not `0.333` — so `(fast 3 (slow 7 p))` composes
exactly instead of drifting. A `double` in the pattern layer is a defect.

Randomness is a pure hash of time, never a stateful PRNG: cycle 400 gives the
same answer every time, and you can reach it without playing 0–399.

## Notation

### `notes` (macro)

`notes` is a macro, so bare symbols are never resolved as vars. A symbol's
*spelling* decides what it is:

| Form | Meaning |
|---|---|
| `[a-g]`, then any of `s # f b`, then an octave | note literal — `c4`, `cs4`, `ef3`, `bb2`, `c-1` |
| `_` | rest |
| any other symbol | left alone; resolves as a var |
| vector | subdivision |
| list | a call, with its arguments rewritten by these same rules |
| number | raw MIDI note number, or a scale degree under `scale` |

Middle C is `c4` = 60. `s` and `#` sharpen; `f` and `b` flatten.

```clojure
(notes c4 e4 g4)          ;;=> notes 60 64 67, three equal steps
```

The body is subdivided across one cycle, and a vector subdivides further:

```clojure
(notes c4 _ [eb4 g4])
;; notes:  60, rest, 63, 67
;; spans:  [0 1/3]  [2/3 5/6]  [5/6 1]
```

That single spelling rule is why `(cyc bb4 a4)` means two alternating notes
while `(rev riff)` still resolves `riff` — inside the same form, with no
unquote operator.

### `note-name->midi`

```clojure
(note-name->midi 'ef3)    ;;=> 51
(note-name->midi 'c-1)    ;;=> 0
```

Accepts a symbol, string or keyword, so `:d3` works too. Returns `nil` if the
spelling is not a note.

## Primitives

| | |
|---|---|
| `(pure v)` | one value, filling every cycle |
| `silence` | nothing, ever |
| `(stack & ps)` | all at once |
| `(slowcat & ps)` / `(cyc & ps)` | one per cycle, in rotation |
| `(fastcat & ps)` / `(sub & ps)` | all squeezed into one cycle |

`cyc` and `sub` are the short aliases you actually type in a performance.

```clojure
(cyc (notes c4) (notes d4))
;; cycle 0 => (60)
;; cycle 1 => (62)
```

All three concatenations lift raw values, so a bare number or keyword works
where a pattern is expected:

```clojure
(sub 0.9 0.5 0.7)         ; same as (sub (pure 0.9) (pure 0.5) (pure 0.7))
```

Lifting uses bare `pure`, **not** `{:note v}` — these carry modulation values
as often as notes, and `with` needs the raw value.

## Time

| | |
|---|---|
| `(fast n p)` | compress to 1/n, repeating n times per cycle |
| `(slow n p)` | stretch across n cycles |
| `(early n p)` / `(late n p)` | shift n cycles earlier / later |
| `(rev p)` | mirror within each cycle |

```clojure
(fast 2 (notes c4 e4))    ;;=> 60 64 60 64
(rev  (notes c4 d4 e4))   ;;=> 64 62 60
```

`rev` keeps cycle N as cycle N — only the contents are mirrored, so it never
shifts material between cycles.

```clojure
(late 1/4 (notes c4))     ;; whole moves from [0 1] to [1/4 5/4]
```

## Structure

| | |
|---|---|
| `(every n f p)` | apply `f` on every nth cycle, from cycle 0 |
| `(iter n p)` | rotate 1/n further each cycle, home after n |
| `(off t f p)` | stack a copy shifted `t` later, with `f` applied to the copy |
| `(superimpose f p)` | stack `(f p)` against the untouched original |
| `(stut n fb t p)` | n copies, `t` apart, `:vel` scaled by `fb` each time |

```clojure
(every 2 rev (notes c4 d4 e4))
;; cycle 0 => (64 62 60)     reversed
;; cycle 1 => (60 62 64)     untouched

(iter 4 (notes c4 d4 e4 f4))
;; cycle 0 => (60 62 64 65)
;; cycle 1 => (62 64 65 60)

(off 1/4 identity (notes c4))
;; wholes  => [0 1] and [1/4 5/4]

(superimpose (partial fast 2) (notes c4))
;;=> (60 60 60)              one original, two from the doubled copy

(stut 3 0.5 1/8 (notes c2))
;; note/vel => [36 1.0] [36 0.5] [36 0.25]
```

`stut` multiplies any existing `:vel`, treating an absent one as `1.0`.

## Rhythm

```clojure
(euclid k n p)        ; k onsets spread as evenly as possible over n steps
(euclid k n rot p)    ; the same, rotated left by rot steps
(euclid-full k n p q) ; q on the rests instead of resting
```

```clojure
(euclid 3 8 (notes c2))
;; spans => [0 1/8] [3/8 1/2] [3/4 7/8]      i.e. x..x..x.

(euclid-full 3 8 (pure :x) (pure :o))
;;=> (:x :o :o :x :o :o :x :o)
```

E(3,8) is the tresillo, E(5,8) the cinquillo. `k <= 0` or `n <= 0` is silence;
`k >= n` fills every step.

## Grammars

### `lsys`

```clojure
(lsys rules axiom n)
```

A Lindenmayer system: rewrite `axiom` for `n` generations, then play the
result across one cycle. `rules` maps a symbol to a vector of symbols; a
symbol with **no** rule is a constant and rewrites to itself.

```clojure
(def fib {0 [0 1], 1 [0]})        ; the Fibonacci word

(lsys fib [0] 0)   ;;=> (0)                          the axiom
(lsys fib [0] 1)   ;;=> (0 1)
(lsys fib [0] 2)   ;;=> (0 1 0)
(lsys fib [0] 3)   ;;=> (0 1 0 0 1)
(lsys fib [0] 4)   ;;=> (0 1 0 0 1 0 1 0)
(lsys fib [0] 5)   ;;=> (0 1 0 0 1 0 1 0 0 1 0 0 1)
```

A numeric symbol becomes `{:note n}` — the same thing a bare number means
inside `notes` — so an L-system over integers drops straight into `scale`:

```clojure
(scale :dorian :d3 (lsys fib [0] 3))
;;=> (50 52 50 50 52)
```

Any other symbol is left as the raw value, so keyword alphabets work:

```clojure
(lsys {:a [:a :b] :b [:a]} [:a] 3)
;;=> (:a :b :a :a :b)

(lsys {:f [:f :+ :f :- :f :- :f :+ :f]} [:f] 1)
;;=> (:f :+ :f :- :f :- :f :+ :f)     :+ and :- have no rule, so they persist
```

Everything lands in one cycle, as `sub` and `notes` do. Long words are meant
to be stretched — generation 6 is 21 symbols, which `slow 4` unfolds six at a
time:

```clojure
(slow 4 (lsys fib [0] 6))          ; 6 of the 21 in cycle 0
```

**Growth guard.** These expand exponentially, and rendering thousands of
events in one cycle would wedge the render thread. Expansion throws past 4096
symbols:

```clojure
(lsys fib [0] 17)
;; mu: lsys reached 4181 symbols at generation 17, over the 4096 cap.
;; Use fewer generations, or a rule that grows more slowly.
```

Safe mid-performance: `mu.player` isolates the throw to that voice, which
replays its last good cycle. `n <= 0` is the axiom itself; an empty axiom is
silence.

## Randomness

| | |
|---|---|
| `(degrade p)` | drop about half the events |
| `(degrade-by amt p)` | drop proportion `amt` (0.0 keeps all, 1.0 drops all) |
| `(sometimes f p)` | apply `f` to about half the events |
| `(sometimes-by amt f p)` | apply `f` to proportion `amt` |

```clojure
(degrade (fast 16 (notes c4)))    ;;=> 9 of the 16 survive
```

`sometimes` splits complementarily — the treated and untreated halves together
are exactly the original, so no event is lost or duplicated.

## Signals

`sine`, `saw`, `tri`, `rand` are **continuous**: they carry `:whole nil`, so
they are never onsets and never trigger on their own. They exist to be sampled
by `with`.

```clojure
(query sine [0 1/2])
;;=> [{:whole nil, :part [0 1/2], :value 1.0}]
```

Sampled at the midpoint of whatever span is queried. Change the rate with
`fast`, not an argument: `(fast 4 sine)`.

## Values

### `fmap`

Apply a function to every event value, leaving all timing untouched.

```clojure
(fmap #(update % :note + 12) (notes c4 e4))
;;=> ({:note 72} {:note 76})
```

### `with`

Merge a key into each event's value. **Structure comes from the left, values
from the right.**

```clojure
(with (notes c4 d4) :chan 3)
;;=> ({:note 60, :chan 3} {:note 62, :chan 3})

(with (notes c4 d4) :cut saw)          ; a signal, sampled per note
;;=> ({:note 60, :cut 0.25} {:note 62, :cut 0.75})
```

A scalar is lifted; a pattern is sampled per event, so it can run at its own
rate and phase against the notes:

```clojure
(with (notes c4 d4 e4 f4) :vel (sub 0.9 0.5 0.7))
;; onset velocities => (0.9 0.9 0.5 0.7)
```

Recognised keys are `:note`, `:vel` (0.0–1.0, or a raw 0–127 integer) and
`:chan`. A per-event `:chan` overrides the voice's channel.

## Harmony

Patterns carry integer scale **degrees**; `scale` maps them to MIDI against a
root. Re-rooting or re-moding a whole line is then one edit.

### `scale`

```clojure
(scale mode root p)
```

`root` is a note-name keyword (`:d3`) or a raw MIDI number. Keywords keep
`scale` an ordinary function, so it composes: `(every 4 #(scale :lydian :d3 %) riff)`.

```clojure
(scale :dorian :d3 (notes 0 2 4 6))   ;;=> (50 53 57 60)
(scale :major  :c4 (notes 0 7 -1))    ;;=> (60 72 59)
```

Degree 7 is the octave; degree −1 falls below the root. Events with no `:note`
pass through untouched, so `scale` is safe downstream of `with`. An unknown
mode throws, naming the ones that exist.

Modes: `:ionian`/`:major`, `:dorian`, `:phrygian`, `:lydian`, `:mixolydian`,
`:aeolian`/`:minor`, `:locrian`, `:harmonic-minor`, `:melodic-minor`,
`:major-pent`, `:minor-pent`, `:blues`, `:chromatic`.

### `chord`

```clojure
(chord p)         ; triad
(chord size p)    ; size-note diatonic stack
```

Each degree becomes degrees d, d+2, d+4… **`chord` runs inside `scale`**, on
degrees, before they become MIDI. Reversed, it would stack semitone offsets on
absolute note numbers and produce nonsense.

```clojure
(scale :major  :c4 (chord 3 (notes 0)))   ;;=> (60 64 67)    C major triad
(scale :major  :c4 (chord 4 (notes 0)))   ;;=> (60 64 67 71) Cmaj7
(scale :dorian :d3 (chord 3 (notes 4)))   ;;=> (57 60 64)    minor, per the mode
```

The quality falls out of the mode, so a progression is correct by construction
and re-harmonises when the root moves. A chord is one onset, not several —
every note carries the source event's timing.

### `arp`

```clojure
(arp mode p)      ; :up :down :updown :downup
```

Spreads notes sharing a `:whole` across the span they occupied, so one chord
becomes n onsets in n slots.

```clojure
(scale :dorian :d3 (arp :up (chord 3 (notes 0))))
;; notes => (50 53 57)
;; spans => [0 1/3] [1/3 2/3] [2/3 1]

(scale :major :c4 (arp :updown (chord 3 (notes 0))))
;;=> (60 64 67 64)      turns at the top without repeating it
```

Anything without a `:note`, and any lone event, passes through untouched.

## Performance

Voices hold **vars**, not pattern values, and the render thread derefs every
registered var once per cycle. Deref-per-cycle *is* swap-at-cycle-edge: hot
redefinition lands in time with no transition machinery.

```clojure
(begin! {:port "Gervill" :bpm 120})   ; nil port takes the first available
(begin!)                              ; defaults: first port, 120 bpm

(def bass (notes c2 _ eb2 g2))
(play! #'bass)                        ; key derived from the var name
(play! :bass #'bass {:chan 1})        ; explicit key and options

(bpm 140)          ; applies at the next cycle boundary
(voices)           ; what is registered

(mute :bass) (unmute :bass)
(solo :bass) (unsolo)
(stop-voice! :bass)

(hush)             ; stop every voice and silence anything sounding
(panic)            ; all-notes-off everywhere, voices left registered
(end!)             ; stop the transport and close the port
```

Passing a bare pattern instead of a var registers a **snapshot** that will not
update when you redefine it. Prefer `#'bass`.

A voice whose pattern throws — typo, nil, wrong arity — is caught per voice.
The error is printed with the voice's key and that voice replays its last
successfully rendered cycle. A typo costs you the edit, not the performance;
one failing voice cannot affect its neighbours or the clock.

`(mu.midi/list-ports)` shows what this JVM can see.

## Web view

```clojure
(web! {:nrepl-port 7888})   ;=> "http://localhost:7890"
(web-off!)
```

A browser editor and performance HUD: modal vim over CodeMirror, the live
voice list, the current cycle's events, and the same notes through a soundfont
in the browser. Needs the `:web` alias (`clojure -M:web`), because mu's core
carries no dependencies.

`web!` binds `127.0.0.1` by default. Pass `:ip "0.0.0.0"` only if you
deliberately want a remote audience view — it also exposes this process's
nREPL, which is arbitrary code execution, to anyone who can reach that
interface.

## Index

| Name | Arities |
|---|---|
| `arp` | `[mode p]` |
| `begin!` | `[]` `[{:keys [port bpm]}]` |
| `bpm` | `[n]` |
| `chord` | `[p]` `[size p]` |
| `cyc` | alias of `slowcat` |
| `degrade` | `[p]` |
| `degrade-by` | `[amt p]` |
| `early` | `[n p]` |
| `end!` | `[]` |
| `euclid` | `[k n p]` `[k n rot p]` |
| `euclid-full` | `[k n p q]` |
| `every` | `[n f p]` |
| `fast` | `[n p]` |
| `fastcat` | `[& ps]` |
| `fmap` | `[f p]` |
| `hush` | `[]` |
| `iter` | `[n p]` |
| `late` | `[n p]` |
| `lsys` | `[rules axiom n]` |
| `mute` | `[k]` |
| `note-name->midi` | `[sym]` |
| `notes` | `[& body]` (macro) |
| `off` | `[t f p]` |
| `panic` | `[]` |
| `play!` | `[x]` `[k x]` `[k x opts]` |
| `pure` | `[v]` |
| `query` | `[p span]` |
| `rand` | signal |
| `rev` | `[p]` |
| `saw` | signal |
| `scale` | `[mode root p]` |
| `silence` | pattern |
| `sine` | signal |
| `slow` | `[n p]` |
| `slowcat` | `[& ps]` |
| `solo` | `[k]` |
| `sometimes` | `[f p]` |
| `sometimes-by` | `[amt f p]` |
| `stack` | `[& ps]` |
| `stop-voice!` | `[k]` |
| `stut` | `[n fb t p]` |
| `sub` | alias of `fastcat` |
| `superimpose` | `[f p]` |
| `tri` | signal |
| `unmute` | `[k]` |
| `unsolo` | `[]` |
| `voices` | `[]` |
| `web!` | `[& args]` |
| `web-off!` | `[]` |
| `with` | `[p k v]` |
