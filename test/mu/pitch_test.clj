(ns mu.pitch-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.pattern :as p]
            [mu.pitch :as pitch]))

(deftest spell-to-midi-round-trips
  (testing "middle C"
    (is (= 60 (pitch/spell->midi {:step :c :alter 0 :octave 4}))))
  (testing "every natural in octave 4"
    (is (= [60 62 64 65 67 69 71]
           (map #(pitch/spell->midi {:step % :alter 0 :octave 4}) pitch/steps))))
  (testing "accidentals"
    (is (= 51 (pitch/spell->midi {:step :e :alter -1 :octave 3})))
    (is (= 51 (pitch/spell->midi {:step :d :alter 1 :octave 3})))
    (is (= 62 (pitch/spell->midi {:step :c :alter 2 :octave 4}))))
  (testing "a missing :alter counts as natural"
    (is (= 60 (pitch/spell->midi {:step :c :octave 4}))))
  (testing "the enharmonic edges, where the letter's octave is not the note's"
    (is (= 60 (pitch/spell->midi {:step :b :alter 1 :octave 3})) "B-sharp 3 sounds as C4")
    (is (= 59 (pitch/spell->midi {:step :c :alter -1 :octave 4})) "C-flat 4 sounds as B3")))

(deftest default-spell-uses-flats
  (testing "naturals"
    (is (= {:step :c :alter 0 :octave 4} (pitch/default-spell 60)))
    (is (= {:step :b :alter 0 :octave 3} (pitch/default-spell 59))))
  (testing "black keys spell flat, matching (notes eb2), never d#2"
    (is (= {:step :e :alter -1 :octave 3} (pitch/default-spell 51)))
    (is (= {:step :b :alter -1 :octave 4} (pitch/default-spell 70))))
  (testing "it always round-trips"
    (is (every? #(= % (pitch/spell->midi (pitch/default-spell %))) (range 0 128))))
  (testing "low and negative octaves"
    (is (= {:step :c :alter 0 :octave -1} (pitch/default-spell 0)))))

(deftest spelled-trusts-only-an-agreeing-spelling
  (let [eb {:step :e :alter -1 :octave 3}]
    (testing "a carried spelling that agrees is returned as written"
      (is (= eb (pitch/spelled {:note 51 :spell eb}))))
    (testing "no carried spelling falls back to the default"
      (is (= eb (pitch/spelled {:note 51}))))
    (testing "a STALE spelling is ignored, not printed"
      (is (= (pitch/default-spell 63) (pitch/spelled {:note 63 :spell eb})))
      (is (not= eb (pitch/spelled {:note 63 :spell eb}))))))
