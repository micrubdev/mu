(ns mu.time
  "Rational cycle arithmetic. Pure: no wall-clock, no state.

  Time is measured in CYCLES and represented as an exact rational
  (Long or clojure.lang.Ratio). Doubles are prohibited here -- nested
  fast/slow must compose exactly rather than drift.")

(defn floor-cycle
  "Greatest integer <= t, exact for rationals. Returns a long."
  ^long [t]
  (cond
    (integer? t) (long t)
    (ratio? t)   (Math/floorDiv (long (numerator t)) (long (denominator t)))
    :else        (throw (IllegalArgumentException.
                          (str "mu.time requires exact rational time, got "
                               (class t) ": " t)))))

(defn sect
  "Intersection of two half-open spans, or nil if they do not overlap.
  Touching spans ([0 1/4] and [1/4 1/2]) do NOT overlap."
  [[b1 e1] [b2 e2]]
  (let [b (max b1 b2)
        e (min e1 e2)]
    (when (< b e) [b e])))

(defn split-cycles
  "Split a half-open span at integer cycle boundaries.

  Returns a vector of spans, each lying within a single cycle. A
  zero-width span is returned as-is: signals are sampled at a point and
  must not be split away to nothing."
  [[b e]]
  (cond
    (= b e) [[b e]]
    (> b e) []
    :else
    (loop [acc (transient []) b b]
      (if (>= b e)
        (persistent! acc)
        (let [nxt (min e (inc (floor-cycle b)))]
          (recur (conj! acc [b nxt]) nxt))))))
