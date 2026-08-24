(ns mu.clock-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.clock :as clk]
            [mu.midi :as m]
            [mu.notation :refer [notes]]
            [mu.pattern :as p]))

(def NPC 1000000000)   ; 1 second per cycle, in nanos

(defn- rendered
  "Render one cycle and return [{:at nanos :spec spec}] in time order.
  Anchors the cycle at 0, so :at reads as an offset within the cycle."
  [voices cyc carry]
  (let [sink (m/recording-sink)
        {:keys [times specs n]} (clk/render-cycle sink voices cyc 0 NPC carry)]
    (mapv (fn [i] {:at (aget ^longs times i) :spec (aget ^objects specs i)})
          (range n))))

(deftest renders-note-on-and-note-off-for-each-onset
  (let [out (rendered {:v {:pattern (notes c4) :chan 0}} 0 [])]
    (is (= 2 (count out)) "one note-on and one note-off")
    (is (= :note-on  (:type (:spec (first out)))))
    (is (= :note-off (:type (:spec (second out)))))
    (is (= 60 (:note (:spec (first out)))))))

(deftest events-are-placed-at-the-right-wall-clock-time
  (let [out (rendered {:v {:pattern (notes c4 d4) :chan 0}} 0 [])
        ons (filter #(= :note-on (:type (:spec %))) out)]
    (is (= [0 (/ NPC 2)] (map :at ons))
        "two notes: cycle start and halfway")))

(deftest times-are-relative-to-the-given-cycle-start
  (testing "the anchor is this cycle's own start, not a global origin"
    (let [sink (m/recording-sink)
          {:keys [times n]} (clk/render-cycle
                              sink {:v {:pattern (notes c4) :chan 0}}
                              3 (* 3 NPC) NPC [])]
      (is (pos? n))
      (is (= (* 3 NPC) (aget ^longs times 0)))))
  (testing "the same cycle at a different anchor moves with it"
    (let [sink (m/recording-sink)
          {:keys [times]} (clk/render-cycle
                            sink {:v {:pattern (notes c4) :chan 0}}
                            3 999 NPC [])]
      (is (= 999 (aget ^longs times 0))
          "this is what makes a tempo change re-anchor cleanly"))))

(deftest output-is-sorted-by-time
  (let [out (rendered {:a {:pattern (notes c4 d4 e4 f4) :chan 0}
                       :b {:pattern (notes g4)          :chan 1}}
                      0 [])]
    (is (= (sort (map :at out)) (map :at out)))))

(deftest note-offs-past-the-cycle-end-become-carry
  (testing "a note filling the whole cycle ends exactly at the boundary"
    (let [sink (m/recording-sink)
          {:keys [carry n]} (clk/render-cycle
                              sink {:v {:pattern (notes c4) :chan 0}}
                              0 0 NPC [])]
      (is (= 2 n) "note-off at the boundary still belongs to this cycle")
      (is (empty? carry))))
  (testing "a note stretched past the boundary carries its off forward"
    (let [sink  (m/recording-sink)
          long-note (p/slow 2 (notes c4))
          {:keys [n carry]} (clk/render-cycle
                              sink {:v {:pattern long-note :chan 0}}
                              0 0 NPC [])]
      (is (= 1 n) "only the note-on lands in this cycle")
      (is (= 1 (count carry)))
      (is (= :note-off (:type (:spec (first carry))))))))

(deftest carry-in-is-emitted-in-the-next-cycle
  (let [carry [{:at-cycle 5/4 :spec {:type :note-off :chan 0 :note 60}}]
        out   (rendered {} 1 carry)]
    (is (= 1 (count out)))
    (is (= (long (* 1/4 NPC)) (:at (first out)))
        "5/4 is a quarter-cycle into cycle 1, which this test anchored at 0")))

(deftest non-onset-fragments-do-not-retrigger
  (testing "a note held across a subdivision fires exactly once"
    (let [out (rendered {:v {:pattern (p/slow 2 (notes c4)) :chan 0}} 0 [])
          ons (filter #(= :note-on (:type (:spec %))) out)]
      (is (= 1 (count ons))))))

(deftest timestamps-are-pre-boxed-for-the-dispatch-thread
  ;; The dispatch thread must not allocate, and passing a primitive long
  ;; to the emit! protocol method would box one Long per message. The
  ;; render thread pre-boxes them into :ats instead.
  (let [sink (m/recording-sink)
        {:keys [times ats n]} (clk/render-cycle
                                sink {:v {:pattern (notes c4 d4) :chan 0}}
                                0 0 NPC [])]
    (is (pos? n))
    (dotimes [i n]
      (is (instance? Long (aget ^objects ats i)) "already a boxed Long")
      (is (= (aget ^longs times i) (aget ^objects ats i))
          "and identical to the primitive time at the same index"))))
