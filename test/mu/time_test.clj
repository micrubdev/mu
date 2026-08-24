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
