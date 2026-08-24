(ns mu.pattern
  "The pattern algebra. A Pattern is a pure function from a span to the
  events active within it.

  Purity boundary: nothing here reads wall-clock time, touches MIDI, or
  holds mutable state. Everything is testable by calling `query`."
  (:refer-clojure :exclude [rand])
  (:require [mu.time :as t]))

(defrecord Pattern [query])

(defn pat
  "Wrap a query function (span -> seq of events) as a Pattern."
  [f]
  (->Pattern f))

(defn pattern?
  [x]
  (instance? Pattern x))

(defn query
  "Ask a pattern for the events active in a span."
  [p span]
  ((:query p) span))

(defn onset?
  "True when this event fragment contains the start of its own note.

  Only onsets trigger. A query span that bisects a note yields a
  fragment whose part starts after its whole -- without this check the
  note would retrigger mid-sustain. Continuous signals (:whole nil)
  are never onsets."
  [{:keys [whole part]}]
  (boolean (and whole (= (first whole) (first part)))))

(def silence
  (pat (fn [_] ())))

(defn pure
  "A pattern repeating one value, filling every cycle."
  [v]
  (pat (fn [sp]
         (for [[b e] (t/split-cycles sp)
               :let  [c (t/floor-cycle b)]]
           {:whole [c (inc c)] :part [b e] :value v}))))

(defn- with-time
  "Build a pattern that maps query times through `fwd` and result event
  times back through `back`. Every linear time transform is this."
  [fwd back p]
  (let [shift (fn [s] (when s [(back (first s)) (back (second s))]))]
    (pat (fn [[b e]]
           (map (fn [ev] (-> ev
                             (update :part shift)
                             (update :whole shift)))
                (query p [(fwd b) (fwd e)]))))))

(defn fast
  "Compress a pattern to 1/n of its length, repeating it n times per cycle."
  [n p]
  (if (zero? n)
    silence
    (with-time #(* % n) #(/ % n) p)))

(defn stack
  "Play patterns simultaneously."
  [& ps]
  (pat (fn [sp] (mapcat #(query % sp) ps))))

(defn slowcat
  "Play one pattern per cycle, in rotation."
  [& ps]
  (let [ps (vec ps)
        n  (count ps)]
    (if (zero? n)
      silence
      (pat (fn [sp]
             (mapcat
               (fn [[b e]]
                 (let [c   (t/floor-cycle b)
                       i   (mod c n)
                       ;; Each sub-pattern should see its OWN cycle count,
                       ;; not the global one, so `every` inside a slowcat
                       ;; counts the cycles it actually plays. At global
                       ;; cycle c the chosen sub-pattern is on its cycle
                       ;; (quot c n), so we query it back by that offset and
                       ;; shift the results forward again.
                       off (- c (Math/floorDiv (long c) (long n)))]
                   (query (with-time #(- % off) #(+ % off) (nth ps i))
                          [b e])))
               (t/split-cycles sp)))))))

(defn fastcat
  "Squeeze all patterns into a single cycle, one after another."
  [& ps]
  (if (empty? ps)
    silence
    (fast (count ps) (apply slowcat ps))))

;; Short aliases -- these are the forms actually typed in a performance.
(def cyc "One item per cycle. Alias of slowcat." slowcat)
(def sub "Subdivide one cycle. Alias of fastcat." fastcat)

(defn slow
  "Stretch a pattern to n cycles long."
  [n p]
  (if (zero? n) silence (fast (/ 1 n) p)))

(defn late
  "Shift a pattern n cycles later in time."
  [n p]
  (with-time #(- % n) #(+ % n) p))

(defn early
  "Shift a pattern n cycles earlier in time."
  [n p]
  (late (- n) p))

(defn rev
  "Reverse a pattern within each cycle. Cycle N stays cycle N; only the
  contents are mirrored, so `rev` never shifts material between cycles."
  [p]
  (pat (fn [sp]
         (mapcat
           (fn [[b e]]
             (let [c    (t/floor-cycle b)
                   ;; mirror x about the centre of cycle c
                   refl  (fn [x] (- (+ c (inc c)) x))
                   flip  (fn [s] (when s [(refl (second s)) (refl (first s))]))]
               (map (fn [ev] (-> ev (update :part flip) (update :whole flip)))
                    (query p [(refl e) (refl b)]))))
           (t/split-cycles sp)))))

(defn every
  "Apply f to the pattern on every nth cycle, starting at cycle 0."
  [n f p]
  (if (<= n 0)
    p
    (let [transformed (f p)]
      (pat (fn [sp]
             (mapcat (fn [[b e]]
                       (let [c (t/floor-cycle b)]
                         (query (if (zero? (mod c n)) transformed p) [b e])))
                     (t/split-cycles sp)))))))

(defn- time-rand
  "Deterministic pseudo-random in [0,1) derived from a time value.

  A stateful PRNG would break the query model: the same span must give
  the same answer every time, and cycle 400 must be reachable without
  having played cycles 0-399. This is a pure hash of time."
  ^double [x]
  (let [v (Math/sin (* (double x) 12345.6789))
        r (* v 43758.5453)]
    (- r (Math/floor r))))

(defn degrade-by
  "Randomly drop a proportion `amt` of events (0.0 keeps all, 1.0 drops all)."
  [amt p]
  (pat (fn [sp]
         (filter #(>= (time-rand (first (:part %))) (double amt))
                 (query p sp)))))

(defn- undegrade-by
  "Keep exactly the events that `degrade-by` with the same amt drops."
  [amt p]
  (pat (fn [sp]
         (filter #(< (time-rand (first (:part %))) (double amt))
                 (query p sp)))))

(defn degrade
  "Randomly drop about half the events."
  [p]
  (degrade-by 0.5 p))

(defn sometimes-by
  "Apply f to a proportion `amt` of events, leaving the rest untouched.
  The two halves are complementary, so no event is lost or duplicated."
  [amt f p]
  (stack (degrade-by amt p)
         (f (undegrade-by amt p))))

(defn sometimes
  "Apply f to about half the events."
  [f p]
  (sometimes-by 0.5 f p))
