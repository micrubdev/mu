(ns mu.midi-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.midi :as m]))

(deftest velocity-conversion
  (is (= 0   (m/vel->midi 0.0)))
  (is (= 127 (m/vel->midi 1.0)))
  (is (= 64  (m/vel->midi 0.5)))
  (testing "out-of-range input is clamped, not wrapped"
    (is (= 127 (m/vel->midi 1.7)))
    (is (= 0   (m/vel->midi -0.3))))
  (testing "integers pass through as raw MIDI velocity"
    (is (= 100 (m/vel->midi 100)))
    (is (= 127 (m/vel->midi 200)))))

(deftest recording-sink-captures-what-was-emitted
  (let [sink (m/recording-sink)
        spec {:type :note-on :chan 0 :note 60 :vel 100}
        enc  (m/encode sink spec)]
    (m/emit! sink enc 12345)
    (is (= [{:at 12345 :spec spec}] (m/log sink)))))

(deftest recording-sink-preserves-order-and-timing
  (let [sink (m/recording-sink)]
    (doseq [[n at] [[60 100] [62 200] [64 300]]]
      (m/emit! sink (m/encode sink {:type :note-on :chan 0 :note n :vel 90}) at))
    (is (= [100 200 300] (map :at (m/log sink))))
    (is (= [60 62 64] (map (comp :note :spec) (m/log sink))))))
