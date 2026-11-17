(ns mu.shape-test
  (:require [clojure.test :refer [are deftest is testing]]
            [mu.kit :as kit]
            [mu.notation :refer [notes]]
            [mu.pattern :as p]
            [mu.shape :as s]))

(defn- ev
  "A hand-built onset event at bar fraction `at` of cycle 0."
  [at value]
  {:whole [at (+ at 1/16)] :part [at (+ at 1/16)] :value value})

(defn- shape-of
  ([evs] (shape-of evs nil nil))
  ([evs meter memory] (s/voice-shape evs 0 meter memory)))

(deftest infer-meter-table
  (are [steps m] (= m (s/infer-meter steps))
    0 [1 1 1 1]   1 [1 1 1 1]   2 [1 1 1 1]   4 [1 1 1 1]
    8 [2 2 2 2]   16 [4 4 4 4]  32 [8 8 8 8]
    3 [1 1 1]     6 [3 3]       12 [3 3 3 3]  24 [6 6 6 6]
    7 [7]         5 [5]         10 [10]       96 [1 1 1 1]))

(deftest hits-are-onsets-at-bar-fractions
  (let [pat    (kit/kit kit/gm (notes :bd _ :sn [:hh :hh]))
        [sh _] (s/voice-shape (p/query pat [3 4]) 3 nil nil)]
    (is (= [0 1/2 3/4 7/8] (map :at (:hits sh))))
    (is (= :bd (:drum (first (:hits sh)))))
    (is (= 36 (:note (first (:hits sh)))))
    (is (= [2 2 2 2] (:meter sh)))
    (is (= :inferred (:source sh)))
    (is (= :drum (:kind sh)))
    (is (= [:bd :sn :hh] (:lanes sh)))))

(deftest non-onset-fragments-are-not-hits
  ;; a fragment whose part starts after its whole is a sustain, not a hit
  (let [frag {:whole [-1/2 1/2] :part [0 1/2] :value {:note 60}}
        [sh _] (shape-of [frag])]
    (is (empty? (:hits sh)))))

(deftest declared-meter-passes-through
  (let [[sh _] (s/voice-shape (p/query (notes c4 c4) [0 1]) 0 [3 3] nil)]
    (is (= [3 3] (:meter sh)))
    (is (= :declared (:source sh)))
    (is (= :pitched (:kind sh)))
    (is (= [] (:lanes sh)))))

(deftest double-times-are-rationalised
  (let [[sh _] (shape-of [(ev 0.25 {:note 60})])]
    (is (= [1/4] (map :at (:hits sh))))
    (is (= [1 1 1 1] (:meter sh)))))

(deftest a-double-that-is-not-a-neat-fraction-is-rounded-to-the-960-grid
  (let [[sh _] (shape-of [(ev 0.1 {:note 60})])]
    (is (= [1/10] (map :at (:hits sh))))))

(deftest control-only-events-are-dropped
  (let [[sh _] (shape-of [(ev 0 {:cc 74 :val 64}) (ev 1/4 {:bend 0.2})])]
    (is (empty? (:hits sh)))
    (is (nil? (:kind sh)))))

(deftest hits-sort-by-time-then-name
  (let [[sh _] (shape-of [(ev 1/2 {:drum :sn}) (ev 0 {:drum :hh}) (ev 0 {:drum :bd})])]
    (is (= [[0 :bd] [0 :hh] [1/2 :sn]] (map (juxt :at :drum) (:hits sh))))))

(deftest lanes-follow-the-kit-order-then-alphabetical
  (let [[sh _] (shape-of [(ev 0 {:drum :zap}) (ev 0 {:drum :hh}) (ev 0 {:drum :bd})
                          (ev 0 {:drum :agogo})])]
    (is (= [:bd :hh :agogo :zap] (:lanes sh)))))

(def ^:private six-eight-full (map #(ev % {:drum :hh}) [0 1/6 1/3 1/2 2/3 5/6]))
(def ^:private six-eight-thin (map #(ev % {:drum :hh}) [0 1/3 2/3]))

(deftest memory-keeps-6-8-under-degrade
  (let [[a mem] (shape-of six-eight-full)
        [b _]   (shape-of six-eight-thin nil mem)]
    (is (= [3 3] (:meter a)))
    (is (= [3 3] (:meter b)))
    (testing "without memory the thin bar alone would read as 3/4"
      (is (= [1 1 1] (:meter (first (shape-of six-eight-thin))))))))

(deftest memory-keeps-a-resting-lane
  (let [[_ mem] (shape-of [(ev 0 {:drum :bd}) (ev 1/2 {:drum :sn})])
        [b _]   (shape-of [(ev 0 {:drum :bd})] nil mem)]
    (is (= [:bd :sn] (:lanes b)))))

(deftest memory-keeps-the-kind-through-an-empty-bar
  (let [[_ mem] (shape-of [(ev 0 {:drum :bd})])
        [b _]   (shape-of [] nil mem)]
    (is (= :drum (:kind b)))
    (is (= [:bd] (:lanes b)))))

(deftest nil-memory-starts-fresh
  (let [[_ mem] (shape-of [(ev 0 {:drum :bd}) (ev 1/2 {:drum :sn})])
        _       mem
        [c _]   (shape-of [(ev 0 {:drum :bd})] nil nil)]
    (is (= [:bd] (:lanes c)))))

(deftest steps-memory-capped-at-64
  (let [[_ m1] (shape-of [(ev 1/32 {:note 60})])
        [_ m2] (shape-of [(ev 1/3 {:note 60})] nil m1)
        [c _]  (shape-of [(ev 0 {:note 60})] nil m2)]
    (is (= [1 1 1 1] (:meter c)) "lcm 96 passed the cap and stays capped")))
