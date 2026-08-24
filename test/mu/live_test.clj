(ns mu.live-test
  (:refer-clojure :exclude [rand])
  (:require [clojure.test :refer [deftest is testing]]
            [mu.live :refer :all]))

(deftest performance-vocabulary-is-available
  (testing "notation"
    (is (some? (notes c4 _ [eb4 g4]))))
  (testing "combinators"
    (is (some? (fast 2 (notes c4))))
    (is (some? (rev (notes c4 d4))))
    (is (some? (every 3 rev (notes c4 d4))))
    (is (some? (cyc (notes c4) (notes d4))))
    (is (some? (with (notes c4) :vel 0.9))))
  (testing "signals"
    (is (some? sine))
    (is (some? rand)))
  (testing "player controls resolve"
    (is (some? (resolve 'mu.live/play!)))
    (is (some? (resolve 'mu.live/hush)))
    (is (some? (resolve 'mu.live/panic)))))

(deftest notes-is-a-macro-here-not-a-function
  (is (:macro (meta (resolve 'mu.live/notes)))
      "re-export must preserve macro-ness or notation breaks"))
