(ns mu.grammar
  "Generative grammars: rules that grow a sequence, which then becomes a
  pattern.

  Where `mu.transform` reshapes a pattern you already have, this
  namespace MAKES one out of a rule and a seed. That is a different job,
  and it is the seam the other generative operators (cellular automata,
  pure Markov walks) would slot into.

  Purity boundary is the same as `mu.pattern`'s: no wall clock, no MIDI,
  no mutable state. Expansion is a pure function of the rules and the
  generation count."
  (:require [mu.pattern :as p]))

(def ^:private LSYS-MAX
  "Cap on the expanded symbol count.

  L-systems grow exponentially: the Fibonacci word reaches 4181 symbols
  by generation 17 and 10946 by generation 20, and an algae rule passes
  a million not long after. Rendering that many events in one cycle
  would wedge the render thread and take the transport with it, so
  `lsys` refuses rather than trying."
  4096)

(defn- expand
  "Rewrite `axiom` for `n` generations. A symbol with no rule is a
  constant and rewrites to itself, which is what lets the classic
  alphabets use +, - and friends without declaring them."
  [rules axiom n]
  (loop [gen 0, xs (vec axiom)]
    (if (>= gen n)
      xs
      (let [nxt (into [] (mapcat (fn [s] (get rules s [s]))) xs)]
        (when (> (count nxt) LSYS-MAX)
          (throw (ex-info (str "mu: lsys reached " (count nxt) " symbols at generation "
                               (inc gen) ", over the " LSYS-MAX " cap. Use fewer "
                               "generations, or a rule that grows more slowly.")
                          {:generation (inc gen) :length (count nxt) :max LSYS-MAX})))
        (recur (inc gen) nxt)))))

(defn lsys
  "A Lindenmayer system: rewrite `axiom` for `n` generations, then play
  the result across one cycle.

  `rules` maps a symbol to a vector of symbols. A symbol with no rule
  is a constant and rewrites to itself.

    (def rules {0 [0 1], 1 [0]})            ; the Fibonacci word
    (scale :dorian :d3 (lsys rules [0] 6))

  A numeric symbol becomes {:note n} -- the same thing a bare number
  means inside `notes` -- so an L-system over integers drops straight
  into `scale`. Any other symbol is left as the raw value, so keyword
  alphabets work with `fmap`.

  Everything lands in one cycle, as `sub` and `notes` do. Long words
  are meant to be stretched: (slow 8 (lsys rules [0] 8)).

  Expansion throws past 4096 symbols -- see LSYS-MAX. `n <= 0` is the
  axiom itself; an empty axiom is silence."
  [rules axiom n]
  (let [xs (expand rules axiom (max 0 n))]
    (if (empty? xs)
      p/silence
      (apply p/fastcat (map #(if (number? %) {:note %} %) xs)))))
