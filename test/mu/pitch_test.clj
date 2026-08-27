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

(deftest advance-walks-letters-and-fixes-the-accidental
  (testing "C4 up two letters, sounding 64, is E4"
    (is (= {:step :e :alter 0 :octave 4}
           (pitch/advance {:step :c :alter 0 :octave 4} 2 64))))
  (testing "E-flat 3 up two letters, sounding 55, is G3"
    (is (= {:step :g :alter 0 :octave 3}
           (pitch/advance {:step :e :alter -1 :octave 3} 2 55))))
  (testing "past B the letter wraps and the octave follows"
    (is (= {:step :c :alter 0 :octave 4}
           (pitch/advance {:step :b :alter 0 :octave 3} 1 60))))
  (testing "backwards, below the root"
    (is (= {:step :b :alter 0 :octave 2}
           (pitch/advance {:step :c :alter 0 :octave 3} -1 47))))
  (testing "the accidental is whatever makes it sound right"
    (is (= {:step :f :alter 1 :octave 4}
           (pitch/advance {:step :d :alter 0 :octave 4} 2 66)))))

(deftest transpose-moves-note-and-spelling-together
  (let [c4 (p/pure {:note 60 :spell {:step :c :alter 0 :octave 4}})
        v  (fn [q] (:value (first (p/query q [0 1]))))]
    (testing "a major third off C4 is E4"
      (is (= {:note 64 :spell {:step :e :alter 0 :octave 4}} (v (pitch/transpose :M3 c4)))))
    (testing "a minor third off C4 is E-flat 4, not D-sharp"
      (is (= {:note 63 :spell {:step :e :alter -1 :octave 4}} (v (pitch/transpose :m3 c4)))))
    (testing "an octave"
      (is (= {:note 72 :spell {:step :c :alter 0 :octave 5}} (v (pitch/transpose :P8 c4)))))
    (testing "an event with no spelling gets only :note moved"
      (is (= {:note 64} (v (pitch/transpose :M3 (p/pure {:note 60}))))))
    (testing "values that are not note maps pass through"
      (is (= 0.5 (v (pitch/transpose :M3 (p/pure 0.5))))))
    (testing "an unknown interval throws, naming the known ones"
      (is (thrown? clojure.lang.ExceptionInfo (pitch/transpose :Q9 c4))))))
