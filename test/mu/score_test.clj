(ns mu.score-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.pattern :as p]
            [mu.player :as player]
            [mu.score :as score]))

(def stomp
  "; forge stomp
%bpm 120  %key d aeolian
%grid 2
%chan drums 9

bass     drums    lead
1        :bd      _
         :hh      5
b3       :sn      b3!
5        :hh      8>
")

(deftest parse-reads-directives
  (let [{:keys [directives]} (score/parse stomp)]
    (is (= 120 (:bpm directives)))
    (is (= [:d :aeolian] (:key directives)))
    (is (= 2 (:grid directives)))
    (is (= {"drums" 9} (:chan directives)))))

(deftest parse-reads-the-header-and-cells-by-column
  (let [{:keys [tracks sections]} (score/parse stomp)]
    (is (= ["bass" "drums" "lead"] tracks))
    (is (= 1 (count sections)))
    (is (= [["1" ":bd" "_"] [nil ":hh" "5"] ["b3" ":sn" "b3!"] ["5" ":hh" "8>"]]
           (:rows (first sections))))))

(deftest a-cell-belongs-to-the-nearest-column-start-on-its-left
  (let [{:keys [sections]} (score/parse "a    b    c\n  1    2  3\n")]
    (is (= [["1" "2" "3"]] (:rows (first sections))))))

(deftest sections-labels-and-repeats
  (let [src "%grid 1\n\nv\n@a x2\nc4\n\n@b\nd4\n\n@a\n"
        {:keys [sections]} (score/parse src)]
    (is (= [{:label "a" :repeat 2 :rows [["c4"]]}
            {:label "b" :repeat 1 :rows [["d4"]]}
            {:label "a" :repeat 1 :rows [["c4"]]}]
           sections)
        "a bare @a after the fact replays section a")))

(deftest unlabelled-sections-are-numbered
  (let [{:keys [sections]} (score/parse "v\nc4\n\nd4\n")]
    (is (= ["1" "2"] (map :label sections)))))

(defn- notes-of [pat cyc]
  (->> (p/query pat [cyc (inc cyc)])
       (filter p/onset?)
       (sort-by (comp first :whole))
       (map (comp :note :value))))

(deftest compile-turns-tracks-into-patterns
  (let [{:strs [bass drums lead]} (score/compile (score/parse stomp))]
    (testing "grid 2: two rows per cycle, so the score is two cycles long"
      (is (= [50] (notes-of bass 0)) "1 in d aeolian")
      (is (= [52 57] (notes-of bass 1)) "b3 lowers aeolian's own F to E; then 5"))
    (testing "an empty cell is a rest"
      (is (= [57] (notes-of lead 0))))
    (testing "drums keep their names for the kit"
      (is (= [{:drum :bd} {:drum :hh}] (map :value (sort-by (comp first :whole) (p/query drums [0 1]))))))
    (testing "articulation survives"
      (is (= [1.0 nil] (map (comp :vel :value) (sort-by (comp first :whole) (p/query lead [1 2])))))
      (is (true? (:legato (:value (last (sort-by (comp first :whole) (p/query lead [1 2]))))))))
    (testing "it loops"
      (is (= [50] (notes-of bass 2))))))

(deftest compile-without-a-key-reads-note-names
  (let [{:strs [v]} (score/compile (score/parse "%grid 1\nv\nc4\n[e4 g4]\n"))]
    (is (= [60] (notes-of v 0)))
    (is (= [64 67] (notes-of v 1)) "a bracketed cell subdivides")))

(deftest compile-honours-sections-and-repeats
  (let [{:strs [v]} (score/compile (score/parse "%grid 1\n\nv\n@a x2\nc4\n\n@b\nd4\n\n@a\n"))]
    (is (= [[60] [60] [62] [60] [60]] (map #(notes-of v %) (range 5))))))

(deftest a-short-last-cycle-is-padded-with-rests
  (let [{:strs [v]} (score/compile (score/parse "%grid 4\nv\nc4\nd4\ne4\n"))]
    (is (= [60 62 64] (notes-of v 0)))
    (is (= [[0 1/4] [1/4 1/2] [1/2 3/4]]
           (map :whole (sort-by (comp first :whole) (filter p/onset? (p/query v [0 1]))))))))

(deftest load-registers-a-voice-per-track
  (player/reset-all!)
  (let [f (java.io.File/createTempFile "score" ".mu")]
    (spit f stomp)
    (try
      (score/load! (.getPath f))
      (is (= #{:bass :drums :lead} (set (keys (player/voices)))))
      (is (= 9 (:chan (:drums (player/voices)))))
      (testing "loading again replaces the voices"
        (spit f "v\nc4\n")
        (score/load! (.getPath f))
        (is (= #{:v} (set (keys (player/voices))))))
      (finally
        (.delete f)
        (player/reset-all!)))))
