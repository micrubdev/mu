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
