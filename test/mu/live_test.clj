(ns mu.live-test
  (:refer-clojure :exclude [rand])
  (:require [clojure.test :refer [deftest is testing]]
            [mu.live :refer :all]
            [mu.pattern :as p]))

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

(deftest tier-1-operators-are-available
  (testing "rhythm"
    (is (some? (euclid 3 8 (notes c2))))
    (is (some? (off 1/8 rev (notes c4 d4))))
    (is (some? (superimpose rev (notes c4 d4)))))
  (testing "harmony"
    (is (some? (scale :dorian :d3 (notes 0 2 4))))
    (is (some? (chord 3 (notes 0 2 4))))))

(deftest a-jam-buffer-line-works-end-to-end
  (testing "euclid, chord and scale compose the way the README claims"
    (let [q    (scale :dorian :d3 (chord 3 (euclid 3 8 (notes 0))))
          evs  (filter p/onset? (query q [0 1]))]
      (is (= 9 (count evs)) "three onsets, three notes each")
      (is (= [50 53 57] (distinct (map (comp :note :value) evs)))))))
