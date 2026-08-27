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

(deftest arp-batch-is-available
  (is (some? (arp :up (notes c4 e4))))
  (is (some? (iter 4 (notes c4 d4))))
  (is (some? (stut 3 0.6 1/16 (notes c2))))
  (is (some? (euclid-full 3 8 (notes c2) (notes d2)))))

(deftest arp-turns-a-chord-into-a-figure
  (testing "the whole stack composes: chord -> arp -> scale"
    (let [q   (scale :dorian :d3 (arp :up (chord 3 (notes 0))))
          evs (->> (query q [0 1]) (filter p/onset?) (sort-by (comp first :part)))]
      (is (= [50 53 57] (map (comp :note :value) evs)))
      (is (= [[0 1/3] [1/3 2/3] [2/3 1]] (map :whole evs))
          "one chord became three onsets in three slots"))))

(deftest lsys-is-available-and-composes-with-scale
  (testing "the Fibonacci word through dorian on d3"
    (let [rules {0 [0 1], 1 [0]}
          q     (scale :dorian :d3 (lsys rules [0] 3))
          ns    (->> (query q [0 1]) (filter p/onset?)
                     (sort-by (comp first :part)) (map (comp :note :value)))]
      (is (= [50 52 50 50 52] ns)))))

(deftest spelling-is-available-in-a-jam-buffer
  (testing "the operators resolve"
    (is (some? (transpose :M3 (notes c4))))
    (is (some? (spelled {:note 60}))))
  (testing "a literal keeps its spelling through a transposition"
    (let [q (transpose :m3 (notes c4))
          v (:value (first (query q [0 1])))]
      (is (= 63 (:note v)))
      (is (= {:step :e :alter -1 :octave 4} (:spell v)) "E-flat, not D-sharp")))
  (testing "scale and transpose compose"
    (let [q  (transpose :P8 (scale :dorian :d3 (notes 0 2)))
          vs (map :value (query q [0 1]))]
      (is (= [62 65] (map :note vs)))
      (is (= [:d :f] (map (comp :step :spell) vs))))))
