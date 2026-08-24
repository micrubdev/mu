(ns mu.player-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mu.midi :as m]
            [mu.notation :refer [notes]]
            [mu.pattern :as p]
            [mu.player :as pl]))

(use-fixtures :each (fn [f] (pl/reset-all!) (f) (pl/reset-all!)))

(deftest play-registers-a-voice
  (pl/play! :a (notes c4))
  (is (contains? (pl/voices) :a)))

(deftest a-var-is-dereferenced-not-snapshotted
  (def ^:dynamic testpat (notes c4))
  (pl/play! :a #'testpat)
  (let [before (get-in (pl/current-voices) [:a :pattern])]
    (alter-var-root #'testpat (constantly (notes d4)))
    (is (not= before (get-in (pl/current-voices) [:a :pattern]))
        "polling the var picks up the redefinition")))

(deftest a-throwing-voice-replays-its-last-good-cycle
  (let [good (notes c4)
        bad  (p/pat (fn [_] (throw (ex-info "boom" {}))))]
    (pl/play! :a good)
    (is (seq (pl/safe-render :a 0)) "the good pattern renders")
    (pl/play! :a bad)
    (testing "the throw is swallowed and the previous cycle replays"
      (let [out (pl/safe-render :a 1)]
        (is (seq out) "still produces events -- the voice did not go silent")))))

(deftest one-failing-voice-does-not-affect-its-neighbours
  (pl/play! :ok  (notes c4))
  (pl/play! :bad (p/pat (fn [_] (throw (ex-info "boom" {})))))
  (let [vs (pl/current-voices)]
    (is (contains? vs :ok) "the healthy voice is still registered"))
  (is (seq (pl/safe-render :ok 0))
      "the healthy voice renders normally despite its neighbour throwing"))

(deftest mute-and-solo
  (pl/play! :a (notes c4))
  (pl/play! :b (notes d4))
  (testing "mute removes one voice"
    (pl/mute :a)
    (is (= #{:b} (set (keys (pl/current-voices)))))
    (pl/unmute :a)
    (is (= #{:a :b} (set (keys (pl/current-voices))))))
  (testing "solo keeps only one"
    (pl/solo :a)
    (is (= #{:a} (set (keys (pl/current-voices)))))
    (pl/unsolo)
    (is (= #{:a :b} (set (keys (pl/current-voices)))))))

(deftest hush-clears-every-voice
  (pl/play! :a (notes c4))
  (pl/play! :b (notes d4))
  (pl/hush)
  (is (empty? (pl/voices))))
