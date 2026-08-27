(ns mu.transform
  "The derived vocabulary: combinators built ON the algebra rather than
  part of it.

  `mu.pattern` is the algebra -- the Pattern record, the query model,
  the primitives, the linear time transforms and the applicative. This
  namespace is everything expressed in terms of those: cycle-indexed
  transforms, euclidean rhythms, the degrade family.

  The split keeps each file holding one idea. Purity boundary is the
  same as `mu.pattern`'s: no wall clock, no MIDI, no mutable state."
  (:require [mu.pattern :as p]
            [mu.time :as t]))

(defn every
  "Apply f to the pattern on every nth cycle, starting at cycle 0."
  [n f p]
  (if (<= n 0)
    p
    (let [transformed (f p)]
      (p/pat (fn [sp]
               (mapcat (fn [[b e]]
                         (let [c (t/floor-cycle b)]
                           (p/query (if (zero? (mod c n)) transformed p) [b e])))
                       (t/split-cycles sp)))))))

(defn superimpose
  "Stack f applied to the pattern against the untouched original."
  [f p]
  (p/stack p (f p)))

(defn off
  "Stack a copy shifted t cycles later, with f applied to that copy.

  t is in cycles and rational, like every other time value here."
  [t f p]
  (p/stack p (f (p/late t p))))

(defn- bjorklund
  "Bjorklund's algorithm: distribute k onsets as evenly as possible over
  n steps. Returns a vector of booleans.

  Only ever called with 0 < k < n -- `euclid` handles the degenerate
  cases before this point, which is also what guarantees progress:
  both groups are non-empty, so m >= 1 and the remainder shrinks."
  [k n]
  (loop [a (repeat k [true])
         b (repeat (- n k) [false])]
    (if (< (count b) 2)
      (vec (mapcat identity (concat a b)))
      (let [m (min (count a) (count b))]
        (recur (mapv into (take m a) (take m b))
               (concat (drop m a) (drop m b)))))))

(defn euclid
  "Distribute k onsets as evenly as possible over n steps of one cycle,
  playing p at each onset and resting otherwise.

  E(3,8) is the tresillo `x..x..x.`; E(5,8) the cinquillo `x.xx.xx.`.
  `rot` rotates the step vector left, and is taken mod n."
  ([k n p] (euclid k n 0 p))
  ([k n rot p]
   (cond
     (or (<= n 0) (<= k 0)) p/silence
     (>= k n)               (p/fast n p)
     :else
     (let [steps (bjorklund k n)
           r     (mod rot n)
           steps (concat (drop r steps) (take r steps))]
       (apply p/fastcat (map #(if % p p/silence) steps))))))

(defn degrade-by
  "Randomly drop a proportion `amt` of events (0.0 keeps all, 1.0 drops all)."
  [amt p]
  (p/pat (fn [sp]
           (filter #(>= (p/time-rand (first (:part %))) (double amt))
                   (p/query p sp)))))

(defn- undegrade-by
  "Keep exactly the events that `degrade-by` with the same amt drops."
  [amt p]
  (p/pat (fn [sp]
           (filter #(< (p/time-rand (first (:part %))) (double amt))
                   (p/query p sp)))))

(defn degrade
  "Randomly drop about half the events."
  [p]
  (degrade-by 0.5 p))

(defn sometimes-by
  "Apply f to a proportion `amt` of events, leaving the rest untouched.
  The two halves are complementary, so no event is lost or duplicated."
  [amt f p]
  (p/stack (degrade-by amt p)
           (f (undegrade-by amt p))))

(defn sometimes
  "Apply f to about half the events."
  [f p]
  (sometimes-by 0.5 f p))
