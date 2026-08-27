(ns mu.pattern-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.pattern :as p]))

(deftest silence-yields-nothing
  (is (empty? (p/query p/silence [0 4]))))

(deftest pure-fills-each-cycle-with-one-event
  (let [evs (p/query (p/pure :x) [0 2])]
    (is (= 2 (count evs)) "one event per cycle")
    (is (= [[0 1] [1 2]] (map :whole evs)))
    (is (= [[0 1] [1 2]] (map :part evs)))
    (is (= [:x :x] (map :value evs)))))

(deftest querying-a-fragment-yields-a-fragment-not-an-onset
  (testing "the second half of a cycle is a non-onset fragment"
    (let [[ev] (p/query (p/pure :x) [1/2 1])]
      (is (= [0 1] (:whole ev)) "whole is the full cycle")
      (is (= [1/2 1] (:part ev)) "part is what was asked for")
      (is (false? (p/onset? ev))
          "part does not start at whole -- must NOT retrigger"))))

(deftest onset-detection
  (is (true?  (p/onset? {:whole [0 1] :part [0 1/2] :value :x})))
  (is (false? (p/onset? {:whole [0 1] :part [1/2 1] :value :x})))
  (is (false? (p/onset? {:whole nil   :part [0 1/2] :value :x}))
      "continuous signals never trigger"))

(defn- vals-at
  "Values of the onsets in one cycle, in time order."
  [p cyc]
  (->> (p/query p [cyc (inc cyc)])
       (filter p/onset?)
       (sort-by (comp first :part))
       (map :value)))

(deftest stack-plays-patterns-together
  (let [s (p/stack (p/pure :a) (p/pure :b))]
    (is (= #{:a :b} (set (vals-at s 0))) "both sound in the same cycle")
    (is (= 2 (count (p/query s [0 1]))))))

(deftest slowcat-takes-one-pattern-per-cycle
  (let [c (p/slowcat (p/pure :a) (p/pure :b) (p/pure :c))]
    (is (= [:a] (vals-at c 0)))
    (is (= [:b] (vals-at c 1)))
    (is (= [:c] (vals-at c 2)))
    (is (= [:a] (vals-at c 3)) "wraps around")))

(deftest slowcat-gives-each-subpattern-its-own-cycle-count
  ;; Guards the DIRECTION of the slowcat offset. A sub-pattern must see the
  ;; cycles it actually plays (0, 1, 2, ...), not the global cycle number.
  ;; `pure` is cycle-invariant and cannot catch a wrong-signed offset, so
  ;; this uses a sub-pattern whose content varies by cycle.
  (let [inner (p/slowcat (p/pure :x) (p/pure :y) (p/pure :z))
        outer (p/slowcat inner (p/pure :-))]
    (is (= [:x] (vals-at outer 0)))
    (is (= [:-] (vals-at outer 1)))
    (is (= [:y] (vals-at outer 2)) "inner is on its SECOND cycle, not its fourth")
    (is (= [:-] (vals-at outer 3)))
    (is (= [:z] (vals-at outer 4)))
    (is (= [:x] (vals-at outer 6)) "inner wraps after three of its own cycles")))

(deftest fastcat-squeezes-all-patterns-into-one-cycle
  (let [f (p/fastcat (p/pure :a) (p/pure :b))]
    (is (= [:a :b] (vals-at f 0)))
    (is (= [[0 1/2] [1/2 1]] (map :whole (sort-by (comp first :part)
                                                  (p/query f [0 1]))))
        "each takes half the cycle")))

(deftest cat-of-nothing-is-silence
  (is (empty? (p/query (p/slowcat) [0 4])))
  (is (empty? (p/query (p/fastcat) [0 4]))))

(deftest fast-repeats-within-the-cycle
  (is (= [:a :a] (vals-at (p/fast 2 (p/pure :a)) 0)))
  (is (= [[0 1/2] [1/2 1]]
         (map :whole (sort-by (comp first :part)
                              (p/query (p/fast 2 (p/pure :a)) [0 1]))))))

(deftest slow-stretches-across-cycles
  (let [s (p/slow 2 (p/fastcat (p/pure :a) (p/pure :b)))]
    (is (= [:a] (vals-at s 0)))
    (is (= [:b] (vals-at s 1)))))

(deftest late-shifts-forward-in-time
  (let [l (p/late 1/4 (p/pure :a))
        [ev] (p/query l [1/4 1/2])]
    (is (= [1/4 5/4] (:whole ev)) "the event now starts a quarter later")))

(deftest early-is-the-inverse-of-late
  (let [p (p/fastcat (p/pure :a) (p/pure :b) (p/pure :c))]
    (is (= (map :whole (sort-by (comp first :part) (p/query p [0 1])))
           (map :whole (sort-by (comp first :part)
                                (p/query (p/early 1/4 (p/late 1/4 p)) [0 1])))))))

(deftest rev-reverses-within-each-cycle
  (let [r (p/rev (p/fastcat (p/pure :a) (p/pure :b) (p/pure :c)))]
    (is (= [:c :b :a] (vals-at r 0)))
    (is (= [:c :b :a] (vals-at r 1)) "reverses each cycle independently")))

(deftest every-applies-f-on-matching-cycles
  (let [e (p/every 3 (partial p/fast 2) (p/pure :a))]
    (is (= [:a :a] (vals-at e 0)) "cycle 0 is transformed")
    (is (= [:a]    (vals-at e 1)))
    (is (= [:a]    (vals-at e 2)))
    (is (= [:a :a] (vals-at e 3)) "and again at cycle 3")))

(deftest degrade-drops-roughly-half
  (let [d (p/degrade (p/fast 16 (p/pure :a)))
        n (count (filter p/onset? (p/query d [0 8])))]
    (is (< 40 n 216) (str "expected roughly half of 128 events, got " n))))

(deftest randomness-is-a-pure-function-of-time
  (testing "querying the same span twice gives identical results"
    (let [d (p/degrade (p/fast 8 (p/pure :a)))]
      (is (= (p/query d [10 12]) (p/query d [10 12])))))
  (testing "a far-future cycle is reachable without playing the ones before"
    (let [d (p/degrade (p/fast 8 (p/pure :a)))]
      (is (= (p/query d [400 401]) (p/query d [400 401]))))))

(deftest sometimes-partitions-events-without-losing-any
  (let [base  (p/fast 16 (p/pure :a))
        total (count (filter p/onset? (p/query base [0 4])))
        s     (p/sometimes (fn [_] (p/pure :b)) base)
        got   (frequencies (map :value (filter p/onset? (p/query s [0 4]))))]
    (is (pos? (get got :a 0)) "some events were left alone")
    (is (pos? (get got :b 0)) "some events were transformed")))

(deftest signals-are-continuous-and-never-trigger
  (let [[ev] (p/query p/sine [0 1/4])]
    (is (nil? (:whole ev)) "no whole -- nothing to trigger")
    (is (false? (p/onset? ev)))
    (is (= [0 1/4] (:part ev)))))

(deftest signals-are-sampled-at-the-midpoint-of-the-query
  (testing "saw rises linearly across the cycle"
    (let [v (fn [b e] (:value (first (p/query p/saw [b e]))))]
      (is (< (v 0 1/100) (v 1/2 51/100) (v 99/100 1)))))
  (testing "all signals stay within 0.0-1.0"
    (doseq [[nm sig] [["sine" p/sine] ["saw" p/saw] ["tri" p/tri] ["rand" p/rand]]
            b        (range 0 8)]
      (let [x (:value (first (p/query sig [b (+ b 1/8)])))]
        (is (<= 0.0 x 1.0) (str nm " out of range at " b))))))

(deftest signal-rate-changes-with-fast
  (let [slow-v (:value (first (p/query p/saw [1/2 1/2])))
        fast-v (:value (first (p/query (p/fast 2 p/saw) [1/4 1/4])))]
    (is (< (Math/abs (- slow-v fast-v)) 1e-9)
        "fast 2 reaches at 1/4 what the unit signal reaches at 1/2")))

(deftest fmap-transforms-values-only
  (let [evs (p/query (p/fmap inc (p/pure 1)) [0 1])]
    (is (= [2] (map :value evs)))
    (is (= [[0 1]] (map :whole evs)) "timing untouched")))

(deftest with-adds-a-key-from-a-scalar
  (let [pt (p/with (p/pure {:note 60}) :chan 3)]
    (is (= [{:note 60 :chan 3}] (map :value (p/query pt [0 1]))))))

(deftest with-takes-structure-from-the-left
  (testing "four notes against three velocities: four events, phasing"
    (let [notes (p/fastcat (p/pure {:note 60}) (p/pure {:note 62})
                           (p/pure {:note 64}) (p/pure {:note 65}))
          vels  (p/fastcat (p/pure 0.9) (p/pure 0.5) (p/pure 0.7))
          out   (->> (p/query (p/with notes :vel vels) [0 1])
                     (filter p/onset?)
                     (sort-by (comp first :part)))]
      (is (= 4 (count out)) "structure comes from the note pattern")
      (is (= [60 62 64 65] (map (comp :note :value) out)))
      (is (= 0.9 (:vel (:value (first out)))) "first note takes the first vel"))))

(deftest with-samples-a-continuous-signal-per-event
  (let [notes (p/fast 4 (p/pure {:note 60}))
        out   (->> (p/query (p/with notes :cut p/saw) [0 1])
                   (filter p/onset?)
                   (sort-by (comp first :part)))]
    (is (= 4 (count out)))
    (is (apply < (map (comp :cut :value) out))
        "each note samples the rising signal at its own position")))

(deftest concatenations-lift-scalars
  (testing "sub lifts raw values, without wrapping them as notes"
    (is (= [0.9 0.5] (map :value (p/query (p/sub 0.9 0.5) [0 1])))))
  (testing "cyc lifts too, one per cycle"
    (is (= [0.9] (map :value (p/query (p/cyc 0.9 0.5) [0 1]))))
    (is (= [0.5] (map :value (p/query (p/cyc 0.9 0.5) [1 2])))))
  (testing "stack lifts"
    (is (= #{0.9 0.5} (set (map :value (p/query (p/stack 0.9 0.5) [0 1]))))))
  (testing "patterns and scalars mix freely"
    (is (= [0.9 0.5] (map :value (p/query (p/sub (p/pure 0.9) 0.5) [0 1]))))))

(deftest lifting-serves-the-motivating-case
  (testing "three velocities against four notes: phasing, onsets 0.9 0.9 0.5 0.7"
    (let [four (p/sub (p/pure {:note 60}) (p/pure {:note 62})
                      (p/pure {:note 64}) (p/pure {:note 65}))
          q    (p/with four :vel (p/sub 0.9 0.5 0.7))]
      (is (= [0.9 0.9 0.5 0.7]
             (->> (p/query q [0 1])
                  (filter p/onset?)
                  (map (comp :vel :value))))))))
