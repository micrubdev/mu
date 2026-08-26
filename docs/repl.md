# The REPL

mu is played from a REPL. There is no main, no file to run, no build step
between an edit and the sound. This is how the loop works.

## Starting one

```
clojure -M:live
```

That prints a port and drops you at a prompt:

```
nREPL server started on port 33919 on host localhost - nrepl://localhost:33919
nREPL 1.3.0
Clojure 1.12.0
OpenJDK 64-Bit Server VM 21.0.11
user=>
```

You get both at once. The `user=>` prompt is a terminal REPL you can type
into; the port is an nREPL server your editor connects to — Calva's *Connect
to a running REPL*, `cider-connect`, or Cursive's remote nREPL configuration.
No `.nrepl-port` file is written in `--interactive` mode, so copy the number
off that line.

Use `:live` for anything that makes sound. It sets `-XX:+UseZGC
-XX:+ZGenerational -XX:+AlwaysPreTouch`; under G1 the p99 is 19.7 ms against a
1 ms budget, so a plain `clj` is not a substitute for playing — see
[Timing](../README.md#timing).

For pure algebra work — querying patterns, no clock, no device — a plain
`clojure -M -e` starts faster and is the quickest way to check what a pattern
means:

```
clojure -M -e "(require '[mu.live :refer :all] '[mu.pattern :as p])
               (p/query (fast 2 (notes c4 e4)) [0 1])"
```

`Ctrl+D`, `(exit)` or `(quit)` leaves. `Ctrl+C` interrupts an evaluation
without killing the JVM. The clock threads are daemons and die with the
process, but call `(end!)` first so the port closes cleanly.

## The web view

`:web` is another way to start the same kind of REPL as `:live` — nREPL
plus a terminal prompt — with the web namespaces on the classpath too, and
its own nREPL port fixed at 7888 rather than random, because you're about
to hand that number to `web!`:

```
clojure -M:web
```

```clojure
(require '[mu.live :refer :all])
(web! {:nrepl-port 7888})
;=> "http://localhost:7890"
```

`:nrepl-port` must be the port *this* process's nREPL is actually
listening on — 7888, given the alias above. Get it wrong (or leave it out)
and the browser's editor pane goes read-only and its `/repl` socket
returns 503; the HUD — voice list, cycle events — keeps working regardless,
since it doesn't touch the nREPL bridge at all.

`web!` starts an HTTP server and two WebSockets and returns the base URL.
`/hud` broadcasts what the render thread taps off the clock; `/repl`
bridges to the nREPL connection above, so an editor attached to 7888 and
the browser can evaluate into the same process at once. `:port` picks the
HTTP port (default 7890; `:port 0`, as the test suite uses, binds a free
one so parallel test runs don't collide). Calling `web!` again replaces
the running server rather than piling up a second one; `(web-off!)` stops
it and is a no-op if nothing is running.

`:ip` picks the interface to bind and defaults to `"127.0.0.1"`; pass
`:ip "0.0.0.0"` (or a specific LAN address) only if you deliberately want
this reachable off the local machine, since `/repl` is an unauthenticated
bridge into this process's nREPL and binding a real interface hands
arbitrary code execution to anyone who can reach it.

**The browser client itself is not built yet** — that's a separate plan.
Today, opening the returned URL in a browser 404s: `web!` stands up the
server side (HTTP, `/hud`, `/repl`, the nREPL bridge) with nothing yet
serving a page at `/`. What already works is the transport underneath it —
a WebSocket client speaking the project's wire protocol can connect to
`/hud` and watch cycle events arrive, or to `/repl` and drive this
process's REPL, before there is anything to look at in a browser.

Called from a REPL that was *not* started with `:web` — a plain `:live`
session, say — `web!` doesn't throw. `mu.live` itself stays
dependency-free, so without the web namespaces on the classpath it prints
a message telling you to restart under `:web`, and returns.

## The jam buffer

Don't type music at the prompt. Keep a scratch file open in your editor and
evaluate forms out of it, so every pattern you are playing is text you can
edit rather than history you have to retype.

```clojure
(ns jam
  (:refer-clojure :exclude [rand])
  (:require [mu.live :refer :all]))
```

The `:refer-clojure :exclude [rand]` is not optional. `rand` is the one name
in `mu.live` that collides with `clojure.core`, and without the exclusion you
get a warning and the wrong `rand`. (`every?` is core; `every` is not, so it
needs no exclusion.)

Evaluate the `ns` form once, `begin!` once, and after that you are only ever
re-evaluating `def`s. The editor keybinding for "evaluate the form under the
cursor" is `Ctrl+Alt+C Enter` in Calva and `C-c C-c` in CIDER; that one key is
the whole interface.

## A first session

```clojure
(mu.midi/list-ports)
;; => ({:name "Gervill" ...} {:name "Real Time Sequencer" ...})

(def bass (notes c2 _ eb2 g2))
(play! :bass #'bass {:chan 0})
(begin! {:port "Gervill" :bpm 120})   ; nil takes the first available port
```

Now edit the pattern in the buffer and evaluate it again:

```clojure
(def bass (notes c2 c2 eb2 g2))
```

The change lands on the next cycle boundary, in time. That is not a
transition system — the render thread derefs each voice's var once per cycle,
so a `def` *is* the swap. This is also why you pass `#'bass` and not `bass`:
a bare pattern is registered as a snapshot and will never update, which looks
exactly like a dead keybinding.

Transport controls, all safe to hit mid-set:

```clojure
(bpm 140)        ; applies at the next cycle boundary
(mute :bass) (unmute :bass)
(solo :bass) (unsolo)
(stop-voice! :bass)
(hush)           ; drop every voice and silence what is sounding
(panic)          ; all-notes-off, voices left registered
(end!)           ; stop the transport, close the port
```

## Reading a pattern instead of hearing it

`query` takes a span in cycles and returns the events in it. This is the
fastest way to answer "why does that sound wrong", and it needs no clock:

```clojure
(require '[mu.pattern :as p])

(p/query (notes c4 _ [eb4 g4]) [0 1])
;; {:whole [0 1/3],   :part [0 1/3],   :value {:note 60}}
;; {:whole [2/3 5/6], :part [2/3 5/6], :value {:note 63}}
;; {:whole [5/6 1],   :part [5/6 1],   :value {:note 67}}
```

Query results come back **unordered**. Sort by `(comp first :part)` before
comparing two patterns by eye, or `rev` and its input will look identical —
the values are the same, only the spans move.

To hear cycle 12 without waiting for it, just ask for it: `(p/query pat [12
13])`. Randomness is a pure function of time, so that answer is the one you
would have got by playing through cycles 0–11.

## Reloading source

Editing a file under `src/` needs a reload:

```clojure
(require '[mu.pattern :as p] :reload)
```

Or just re-evaluate the changed `defn` — the running clock picks it up on the
next cycle like any other redefinition. `:reload-all` pulls the dependency
chain with it and will re-run `defonce`-free state; the registry in
`mu.player` is `defonce`, so voices survive.

## Watching what the clock emits, with no audio device

A recording sink logs every message instead of sounding it, which makes the
whole transport testable from the REPL on a machine with no MIDI output:

```clojure
(require '[mu.midi :as midi] '[mu.clock :as clk] '[mu.player :as pl])

(def sink (midi/recording-sink))
(def t (clk/start! {:sink sink :voices-fn pl/current-voices :bpm 120}))

(play! :bass #'bass {:chan 0})
(Thread/sleep 3000)
(midi/log sink)
;; ({:at 73342687288397 :spec {:type :note-on :chan 0 :note 36 :vel 0.8}} ...)

(clk/stop! t)
```

This is what `begin!` does, minus `midi/open-sink!`. Reach for it when
`begin!` throws `MidiUnavailableException: Can not open line` — Gervill is a
software synth and needs a real audio output line, so on a headless box
there is nothing to open and no way to hear anything, but the clock, the
patterns and the encoding all still work.

## Things that will bite you

- **`play!` takes options as a map**, not trailing keywords:
  `(play! :bass #'bass {:chan 1})`. The keyword form throws an arity error.
- **The concatenations do not lift scalars.** `(sub (pure 0.9) (pure 0.5))`,
  never `(sub 0.9 0.5)` — a raw number is an NPE at query time, which
  surfaces as a voice-threw message rather than at the prompt.
- **A voice that throws does not stop the set.** The error prints with the
  voice's key and that voice replays its last good cycle, so watch the REPL
  output for `mu: voice :bass threw (...)` — silence that stays interesting
  is how a typo sounds.
