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
