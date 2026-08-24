(ns mu.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.time :as t]))

(deftest floor-cycle-is-exact-on-rationals
  (testing "floors toward negative infinity, exactly"
    (is (= 0 (t/floor-cycle 0)))
    (is (= 0 (t/floor-cycle 7/8)))
    (is (= 1 (t/floor-cycle 1)))
    (is (= 1 (t/floor-cycle 9/8)))
    (is (= -1 (t/floor-cycle -1/8)))
    (is (= -2 (t/floor-cycle -9/8)))))

(deftest sect-intersects-spans
  (is (= [1/4 1/2] (t/sect [0 1/2] [1/4 3/4])))
  (is (= [1/4 1/2] (t/sect [1/4 3/4] [0 1/2])))
  (is (nil? (t/sect [0 1/4] [1/2 1])) "disjoint spans have no intersection")
  (is (nil? (t/sect [0 1/4] [1/4 1/2])) "touching but not overlapping"))

(deftest split-cycles-breaks-at-integer-boundaries
  (testing "a span inside one cycle is returned unchanged"
    (is (= [[1/4 1/2]] (t/split-cycles [1/4 1/2]))))
  (testing "a span crossing a boundary is split there"
    (is (= [[1/2 1] [1 3/2]] (t/split-cycles [1/2 3/2]))))
  (testing "a multi-cycle span splits into whole cycles"
    (is (= [[0 1] [1 2] [2 3]] (t/split-cycles [0 3]))))
  (testing "zero-width spans survive -- signals are sampled at a point"
    (is (= [[1/2 1/2]] (t/split-cycles [1/2 1/2]))))
  (testing "negative time splits correctly too"
    (is (= [[-1/2 0] [0 1/2]] (t/split-cycles [-1/2 1/2])))))

(deftest split-cycles-is-lossless
  (testing "the pieces reassemble into the original span"
    (doseq [sp [[0 3] [1/3 7/3] [-5/4 1/4] [1/8 1/8]]]
      (let [parts (t/split-cycles sp)]
        (is (= (first sp) (ffirst parts)) (str "start preserved for " sp))
        (is (= (second sp) (second (last parts))) (str "end preserved for " sp))
        (is (every? (fn [[b e]] (<= b e)) parts) "no inverted pieces")))))
