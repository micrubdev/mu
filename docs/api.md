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
- [Harmony](#harmony) — `scale`, `chord`, `arp`, `transpose`
- [Percussion](#percussion) — `kit`, `gm`
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

### How notes are written

An event's `:note` says how it **sounds**; an optional `:spell` says how it is
**written**. MIDI 51 is both D♯3 and E♭3 — same sound, different music.

```clojure
(notes ef3)     ;;=> {:note 51, :spell {:step :e, :alter -1, :octave 3}}
(notes 51)      ;;=> {:note 51}          a raw number has no spelling to know
```

`scale` spells its degrees by advancing the root's letter, so degree 2 of D
dorian is an F rather than an E♯:

```clojure
(scale :dorian :d3 (notes 0 1 2 3))
;; note/letter => ([50 :d] [52 :e] [53 :f] [55 :g])
```

`:blues`, `:chromatic` and a raw MIDI root have no canonical letter per degree
and spell nothing. Guessing would print a wrong glyph:

```clojure
(scale :chromatic :c4 (notes 0))   ;;=> {:note 60}
```

### `transpose`

```clojure
(transpose interval p)   ; :P1 :m2 :M2 :m3 :M3 :P4 :A4 :d5 :P5 :m6 :M6 :m7 :M7 :P8
```

Moves the pattern by a named interval, keeping the spelling exact:

```clojure
(transpose :M3 (notes c4))   ;;=> {:note 64, :spell {:step :e, :alter 0, :octave 4}}
(transpose :m3 (notes c4))   ;;=> {:note 63, :spell {:step :e, :alter -1, :octave 4}}
```

Semitones could not do this — up one from C is equally C♯ or D♭ — which is why
the interval is named rather than numeric. An event with no `:spell` gets only
`:note` moved. An unknown interval throws, naming the known ones.

### `spelled`

Spelling is **advisory**: nothing stops `(update % :note + 12)` leaving it
stale. `spelled` is the only reader, and returns a carried spelling only when
it agrees with `:note`:

```clojure
(spelled {:note 63})                                       ;;=> {:step :e, :alter -1, :octave 4}
(spelled {:note 63 :spell {:step :e :alter -1 :octave 3}})  ;;=> {:step :e, :alter -1, :octave 4}
```

The second is the drift case — the carried E♭3 does not sound as 63, so it is
ignored and a plain spelling used. Defaults are **flats**, matching mu's own
note names: it writes `eb2`, never `d#2`.

## Percussion

A keyword in a `notes` body is a drum name, carried as `{:drum :bd}`. It is
not a pitch: percussion note numbers are a lookup table, not a scale, and
transposing a drum part up a fifth is meaningless. A *kit* resolves the name
to a number.

### `kit`

`[k p]`

Resolve `p`'s drum names against kit `k`.

```clojure
(query (kit gm (notes :bd :sn)) [0 1])
;;=> {:chan 9, :note 36, :drum :bd}
;;   {:chan 9, :note 38, :drum :sn}
```

A kit is a plain map from drum name to either a MIDI number — shorthand for
`{:note n}` — or an event fragment merged whole:

```clojure
(def r909 {:bd 36
           :sn 40
           :rim {:note 37 :vel 0.5}})
```

Precedence runs channel default, then kit entry, then the event's own keys,
so a `:vel` you set with `with` or `fmap` survives a kit that ghosts that
drum. `:chan 9` is the default because a drum name means percussion, and
channel 10 is what percussion means in MIDI; a sampler kit sets `:chan` per
entry to say otherwise.

An event that already has a `:note` is left alone. `kit` is therefore
idempotent, and an inner kit wins:

```clojure
(def beat (kit r909 (notes :bd _ :sn)))
(play! :drums #'beat)              ; r909, not the voice's default
```

`kit` is a no-op on pitched patterns, which is why `play!` can apply one to
every voice. An unknown drum name throws; `safe-render` catches it, replays
the last good cycle, and the web HUD shows the error, so a typo mid-set
costs you the edit and not the performance.

### `gm`

The General MIDI percussion map: notes 35–81 under descriptive names —
`:bass-drum`, `:acoustic-snare`, `:closed-hihat`, `:low-mid-tom`, `:crash`,
`:cabasa`, … — plus the short aliases:

| Alias | Name | Note |
|---|---|---|
| `:bd` | `:bass-drum` | 36 |
| `:bd2` | `:acoustic-bass-drum` | 35 |
| `:sn` | `:acoustic-snare` | 38 |
| `:sn2` | `:electric-snare` | 40 |
| `:rim` | `:side-stick` | 37 |
| `:cp` | `:hand-clap` | 39 |
| `:hh` | `:closed-hihat` | 42 |
| `:ph` | `:pedal-hihat` | 44 |
| `:oh` | `:open-hihat` | 46 |
| `:lt` | `:low-floor-tom` | 41 |
| `:ft` | `:high-floor-tom` | 43 |
| `:mt` | `:low-mid-tom` | 47 |
| `:ht` | `:hi-mid-tom` | 48 |
| `:cr` | `:crash` | 49 |
| `:rd` | `:ride` | 51 |
| `:tb` | `:tambourine` | 54 |
| `:cb` | `:cowbell` | 56 |

`gm` is the kit `play!` uses when a voice gives no `:kit`.

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

(program! 0 18)    ; channel 0 onto a GM patch, now
(cc! 0 74 64)      ; a control change, now

(mute :bass) (unmute :bass)
(solo :bass) (unsolo)
(stop-voice! :bass)

(hush)             ; stop every voice and silence anything sounding
(panic)            ; all-notes-off everywhere, voices left registered
(end!)             ; stop the transport and close the port
```

`program!` and `cc!` send immediately rather than joining the schedule.
A patch is a mode a channel is in, not an event in a pattern: putting it
in pattern data would mean tracking a last-program per channel on a
render path that is pure by design, and re-sending it every cycle
otherwise. Set it once. Both are no-ops when the transport is stopped,
like `panic`, and both throw on a value outside 0-127 rather than
clamping — a clamped patch number is a typo you never find.

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
| `cc!` | `[ch n v]` |
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
| `gm` | value (a map) |
| `hush` | `[]` |
| `iter` | `[n p]` |
| `kit` | `[k p]` |
| `late` | `[n p]` |
| `lsys` | `[rules axiom n]` |
| `mute` | `[k]` |
| `note-name->midi` | `[sym]` |
| `notes` | `[& body]` (macro) |
| `off` | `[t f p]` |
| `panic` | `[]` |
| `play!` | `[x]` `[k x]` `[k x opts]` |
| `program!` | `[ch n]` |
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
| `spelled` | `[event]` |
| `stut` | `[n fb t p]` |
| `sub` | alias of `fastcat` |
| `superimpose` | `[f p]` |
| `transpose` | `[interval p]` |
| `tri` | signal |
| `unmute` | `[k]` |
| `unsolo` | `[]` |
| `voices` | `[]` |
| `web!` | `[& args]` |
| `web-off!` | `[]` |
| `with` | `[p k v]` |
