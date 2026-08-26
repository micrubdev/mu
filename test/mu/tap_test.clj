(ns mu.tap-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mu.tap :as tap]))

(use-fixtures :each (fn [f] (tap/reset-all!) (f) (tap/reset-all!)))

(deftest publish-with-no-subscribers-is-a-no-op
  (is (false? (tap/any?)))
  (is (nil? (tap/publish! :x))))

(deftest nil-publish-is-a-no-op
  (let [t (tap/subscribe!)]
    (is (nil? (tap/publish! nil)))
    (is (zero? (tap/dropped t)))
    (is (nil? (tap/poll! t 10)))))

(deftest a-subscriber-receives-published-values-in-order
  (let [t (tap/subscribe!)]
    (is (true? (tap/any?)))
    (tap/publish! :a)
    (tap/publish! :b)
    (is (= :a (tap/poll! t 100)))
    (is (= :b (tap/poll! t 100)))))

(deftest every-subscriber-receives-every-value
  (let [t1 (tap/subscribe!)
        t2 (tap/subscribe!)]
    (tap/publish! :a)
    (is (= :a (tap/poll! t1 100)))
    (is (= :a (tap/poll! t2 100)))))

(deftest poll-returns-nil-when-empty
  (let [t (tap/subscribe!)]
    (is (nil? (tap/poll! t 1)))))

(deftest a-full-tap-drops-instead-of-blocking
  (testing "publishing far past capacity returns immediately and counts drops"
    (let [t (tap/subscribe!)]
      (dotimes [i 500] (tap/publish! i))
      (is (pos? (tap/dropped t)) "drops were counted")
      (is (= 0 (tap/poll! t 10)) "the oldest values survived, the newest were dropped"))))

(deftest a-full-tap-does-not-starve-a-healthy-one
  (let [slow (tap/subscribe!)
        fast (tap/subscribe!)]
    (dotimes [i 500] (tap/publish! i) (tap/poll! fast 10))
    (is (pos? (tap/dropped slow)))
    (is (zero? (tap/dropped fast)))))

(deftest unsubscribe-stops-delivery
  (let [t (tap/subscribe!)]
    (tap/unsubscribe! t)
    (is (false? (tap/any?)))
    (tap/publish! :a)
    (is (nil? (tap/poll! t 10)))))
