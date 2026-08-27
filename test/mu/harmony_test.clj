(ns mu.harmony-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.harmony :as h]
            [mu.pattern :as p]))

(defn- degrees->notes
  "Run a sequence of integer degrees through a scale, in cycle order."
  [mode root ds]
  (->> (apply p/sub (map #(p/pure {:note %}) ds))
       (h/scale mode root)
       (#(p/query % [0 1]))
       (map (comp :note :value))))

(deftest scale-maps-degrees-to-midi
  (testing "degree 0 is the root"
    (is (= [60] (degrees->notes :major :c4 [0]))))
  (testing "degree 7 is the octave above"
    (is (= [72] (degrees->notes :major :c4 [7]))))
  (testing "degree -1 falls below the root: the leading tone"
    (is (= [59] (degrees->notes :major :c4 [-1]))))
  (testing "degree -7 is a full octave below"
    (is (= [48] (degrees->notes :major :c4 [-7]))))
  (testing "a whole major octave"
    (is (= [60 62 64 65 67 69 71 72]
           (degrees->notes :major :c4 (range 8)))))
  (testing "dorian on d3"
    (is (= [50 52 53 55 57 59 60 62]
           (degrees->notes :dorian :d3 (range 8))))))

(deftest scale-accepts-either-root-form
  (testing "a raw MIDI root agrees with the note-name keyword"
    (is (= (degrees->notes :dorian :d3 (range 8))
           (degrees->notes :dorian 50 (range 8))))))

(deftest scale-handles-non-seven-note-modes
  (testing "minor pentatonic wraps after five degrees"
    (is (= [60 63 65 67 70 72] (degrees->notes :minor-pent :c4 (range 6)))))
  (testing "chromatic wraps after twelve"
    (is (= [60 61 72] (degrees->notes :chromatic :c4 [0 1 12])))))

(deftest scale-aliases-agree
  (is (= (degrees->notes :major :c4 (range 8))
         (degrees->notes :ionian :c4 (range 8))))
  (is (= (degrees->notes :minor :c4 (range 8))
         (degrees->notes :aeolian :c4 (range 8)))))

(deftest scale-rounds-non-integer-degrees
  (testing "halves round up, so 3/2 becomes degree 2"
    (is (= (degrees->notes :major :c4 [2]) (degrees->notes :major :c4 [3/2])))))

(deftest scale-rejects-unknown-modes
  (testing "a typo throws, naming the known modes"
    (is (thrown? clojure.lang.ExceptionInfo
                 (h/scale :dorain :c4 (p/pure {:note 0}))))))

(deftest scale-rejects-bad-roots
  (is (thrown? clojure.lang.ExceptionInfo
               (h/scale :major :h9 (p/pure {:note 0})))))

(deftest scale-leaves-non-note-values-alone
  (testing "a bare scalar survives, so scale is safe downstream of with"
    (is (= [0.5] (map :value (p/query (h/scale :major :c4 (p/pure 0.5)) [0 1])))))
  (testing "a map with no :note key survives"
    (is (= [{:vel 0.5}]
           (map :value (p/query (h/scale :major :c4 (p/pure {:vel 0.5})) [0 1])))))
  (testing "sibling keys are preserved alongside the mapped note"
    (is (= [{:note 64 :vel 0.5}]
           (map :value (p/query (h/scale :major :c4 (p/pure {:note 2 :vel 0.5}))
                                [0 1]))))))

(deftest scale-preserves-timing
  (let [src (p/sub (p/pure {:note 0}) (p/pure {:note 2}))
        before (map (juxt :whole :part) (p/query src [0 1]))
        after  (map (juxt :whole :part) (p/query (h/scale :major :c4 src) [0 1]))]
    (is (= before after) "scale is a value map; it must not move anything")))
