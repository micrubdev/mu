(ns mu.shape
  "What one bar of a voice looks like: its hits as bar fractions, its
  meter, and its drum lanes. Pure -- the web view's circle is drawn from
  this, and nothing here touches a clock or a thread.

  One cycle is one bar. A meter is a grouping vector: [3 3] is 6/8,
  [2 2 2] is 3/4, [2 2 3] is 7/8. It shapes the drawing only, never the
  sound.

  A voice's shape carries memory across bars so the drawing holds still
  while `degrade`, `every` and friends drop hits: the guessed grid only
  ever widens and a resting drum keeps its lane. The caller resets the
  memory (passes nil) when the pattern itself changes."
  (:require [mu.pattern :as p]))

(def ^:private max-steps
  "Above this the guess gives up and draws plain 4/4 beats -- a jittery
  or Double-timed pattern must not draw hundreds of ticks."
  64)

(def lane-order
  "Ring order for drum lanes, inside out. Names not listed follow,
  alphabetically."
  [:bd2 :bd :rim :sn :sn2 :cp :lt :ft :mt :ht :hh :ph :oh :rd :cr])

(def ^:private lane-rank (zipmap lane-order (range)))

(defn- power-of-two? [n] (and (pos? n) (zero? (bit-and n (dec n)))))

(defn infer-meter
  "Guess a grouping vector from a bar's step count."
  [steps]
  (let [steps (long steps)]
    (cond
      (> steps max-steps)                    [1 1 1 1]
      (<= steps 4)                           (case steps 3 [1 1 1] (0 1 2 4) [1 1 1 1] [steps])
      (power-of-two? steps)                  (vec (repeat 4 (quot steps 4)))
      (= steps 6)                            [3 3]
      (and (zero? (mod steps 12))
           (power-of-two? (quot steps 12)))  (vec (repeat 4 (quot steps 4)))
      :else                                  [steps])))

(defn- exact
  "A bar fraction as an exact rational. Doubles snap to a 1/960 grid
  first, so 0.1 is 1/10 rather than a 54-bit denominator."
  [x]
  (if (or (ratio? x) (integer? x))
    x
    (/ (Math/round (* 960.0 (double x))) 960)))

(defn- denom [x] (if (ratio? x) (denominator x) 1))

(defn- gcd [a b] (if (zero? b) a (recur b (mod a b))))
(defn- lcm [a b] (quot (* a b) (gcd a b)))

(defn- widen
  "Combine remembered steps with this bar's. Once past the cap it stays
  past the cap."
  [old new]
  (if (> old max-steps)
    old
    (let [l (lcm old new)] (if (> l max-steps) (inc max-steps) l))))

(defn- order-lanes [lanes]
  (vec (sort-by (fn [k] [(lane-rank k Long/MAX_VALUE) (name k)]) lanes)))

(defn- hit [cycle-n {:keys [whole value]}]
  (cond-> {:at (exact (- (first whole) cycle-n)) :vel (get value :vel 0.8)}
    (:drum value) (assoc :drum (:drum value))
    (:note value) (assoc :note (:note value))))

(defn voice-shape
  "Shape one bar. `events` are a cycle's query result, `meter` a declared
  grouping vector or nil, `memory` what the previous call returned (or nil
  to start fresh). Returns [shape memory']. Keys the caller adds to the
  memory are carried through."
  [events cycle-n meter memory]
  (let [hits     (->> events
                      (filter p/onset?)
                      (filter #(or (:drum (:value %)) (:note (:value %))))
                      (map #(hit cycle-n %))
                      (sort-by (fn [h] [(:at h) (some-> (:drum h) name) (:note h -1)]))
                      vec)
        bar-kind (cond (some :drum hits) :drum (seq hits) :pitched)
        steps    (widen (:steps memory 1) (reduce lcm 1 (map (comp denom :at) hits)))
        lanes    (into (set (:lanes memory)) (keep :drum) hits)
        kind     (or (:kind memory) bar-kind)]
    [{:meter  (or meter (infer-meter steps))
      :source (if meter :declared :inferred)
      :kind   kind
      :lanes  (order-lanes lanes)
      :hits   hits}
     (assoc memory :steps steps :lanes lanes :kind kind)]))
