(ns mu.notation-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.pattern :as p]
            [mu.notation :refer [notes note-name->midi]]))

(defn- onset-values [pt cyc]
  (->> (p/query pt [cyc (inc cyc)])
       (filter p/onset?)
       (sort-by (comp first :part))
       (map :value)))

(deftest note-names-parse-to-midi
  (testing "middle C is 60"
    (is (= 60 (note-name->midi 'c4))))
  (testing "accidentals, both spellings"
    (is (= 61 (note-name->midi 'cs4)))
    (is (= 61 (note-name->midi 'c#4)))
    (is (= 63 (note-name->midi 'ef4)))
    (is (= 63 (note-name->midi 'eb4))))
  (testing "b is both a letter and a flat"
    (is (= 47 (note-name->midi 'b2)))
    (is (= 46 (note-name->midi 'bb2)) "B-flat 2"))
  (testing "octaves"
    (is (= 0  (note-name->midi 'c-1)))
    (is (= 72 (note-name->midi 'c5))))
  (testing "non-notes are not notes"
    (is (nil? (note-name->midi 'riff)))
    (is (nil? (note-name->midi 'fast)))
    (is (nil? (note-name->midi 'h4)))))

(deftest notes-builds-a-subdivided-pattern
  (is (= [{:note 60} {:note 62}] (onset-values (notes c4 d4) 0)))
  (is (= [[0 1/2] [1/2 1]]
         (map :whole (sort-by (comp first :part)
                              (p/query (notes c4 d4) [0 1]))))))

(deftest underscore-is-a-rest
  (is (= [{:note 60}] (onset-values (notes c4 _) 0)))
  (is (= 1 (count (p/query (notes c4 _) [0 1])))))

(deftest vectors-subdivide
  (let [pt (notes c4 [d4 e4])]
    (is (= [{:note 60} {:note 62} {:note 64}] (onset-values pt 0)))
    (is (= [[0 1/2] [1/2 3/4] [3/4 1]]
           (map :whole (sort-by (comp first :part) (p/query pt [0 1])))))))

(deftest raw-numbers-are-midi-notes
  (is (= [{:note 36}] (onset-values (notes 36) 0))))

(deftest lists-are-calls-whose-arguments-are-rewritten
  (testing "note literals work inside a call"
    (is (= [{:note 70}] (onset-values (notes (p/cyc bb4 a4)) 0)))
    (is (= [{:note 69}] (onset-values (notes (p/cyc bb4 a4)) 1))))
  (testing "a non-note symbol inside a call still resolves as a var"
    (let [riff (notes c4 d4)]
      (is (= [{:note 62} {:note 60}] (onset-values (notes (p/rev riff)) 0))))))
