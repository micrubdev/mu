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
