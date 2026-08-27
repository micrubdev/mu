(ns mu.preview-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.notation :refer [notes]]
            [mu.pattern :as p]
            [mu.preview :as pv]))

(defn- v [pattern] {:pattern pattern :chan 0})

(deftest notes-in-reads-one-cycle-as-display-tuples
  (testing "offsets are relative to the cycle, and stay rational"
    (is (= [{:b 0 :e 1/2 :note 60 :chan 0}
            {:b 1/2 :e 1 :note 64 :chan 0}]
           (pv/notes-in (v (notes c4 e4)) 0))))
  (testing "the same pattern at cycle 7 gives the same offsets"
    (is (= (pv/notes-in (v (notes c4 e4)) 0)
           (pv/notes-in (v (notes c4 e4)) 7))))
  (testing "a per-event :chan overrides the voice's"
    (is (= [3] (map :chan (pv/notes-in (v (p/with (notes c4) :chan 3)) 0)))))
  (testing "a note sustaining past the boundary keeps :e over 1"
    (is (= [{:b 0 :e 2 :note 60 :chan 0}]
           (pv/notes-in (v (p/slow 2 (notes c4))) 0))))
  (testing "continuous signals have no :whole and are skipped"
    (is (= [] (pv/notes-in (v p/sine) 0)))))

(deftest diff-classifies-against-the-whole-tuple
  (let [a {:b 0 :e 1/2 :note 60 :chan 0}
        b {:b 1/2 :e 1 :note 64 :chan 0}
        c {:b 1/2 :e 1 :note 67 :chan 0}]
    (testing "identical timelines are all same"
      (is (= ["same" "same"] (map :s (pv/diff [a b] [a b])))))
    (testing "an added note"
      (is (= [["same" 60] ["added" 67]]
             (map (juxt :s :note) (pv/diff [a] [a c])))))
    (testing "a removed note stays in place, marked"
      (is (= [["same" 60] ["removed" 64]]
             (map (juxt :s :note) (pv/diff [a b] [a])))))
    (testing "a duration change is an added/removed pair, not a modification"
      (let [b' (assoc b :e 3/4)]
        (is (= #{"added" "removed"}
               (set (map :s (remove #(= "same" (:s %)) (pv/diff [a b] [a b']))))))))))

(deftest voice-preview-reports-change-and-truncation
  (let [a {:b 0 :e 1/2 :note 60 :chan 0}]
    (testing "unchanged"
      (let [r (pv/voice-preview [a] [a])]
        (is (false? (:changed r)))
        (is (= ["same"] (map :s (:notes r))))
        (is (nil? (:truncated r)))))
    (testing "changed"
      (is (true? (:changed (pv/voice-preview [a] [])))))
    (testing "over 512 notes truncates and says so"
      (let [many (mapv #(hash-map :b % :e % :note 60 :chan 0) (range 600))
            r    (pv/voice-preview [] many)]
        (is (= 512 (count (:notes r))))
        (is (true? (:truncated r)))))))
