(ns mu.player-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mu.clock :as clk]
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

;; ---- kits -------------------------------------------------------------

(def ^:private beat-under-test (notes :bd))

(deftest a-drum-voice-resolves-through-the-default-kit
  (pl/play! :d (notes :bd))
  (is (= [{:drum :bd :note 36 :chan 9}]
         (map :value (p/query (:pattern (:d (pl/current-voices))) [0 1])))))

(deftest a-voice-kit-option-overrides-the-default
  (pl/play! :d (notes :bd) {:kit {:bd 99}})
  (is (= [{:drum :bd :note 99 :chan 9}]
         (map :value (p/query (:pattern (:d (pl/current-voices))) [0 1])))))

(deftest a-pitched-voice-is-unchanged-by-the-kit
  (pl/play! :b (notes c4))
  (is (= [{:note 60 :spell {:step :c :alter 0 :octave 4}}]
         (map :value (p/query (:pattern (:b (pl/current-voices))) [0 1])))))

(deftest redefining-a-drum-var-still-lands
  (testing "the kit is applied after the deref, so the live-coding model holds"
    (pl/play! :d #'beat-under-test)
    (is (= [{:drum :bd :note 36 :chan 9}]
           (map :value (p/query (:pattern (:d (pl/current-voices))) [0 1]))))
    (with-redefs [beat-under-test (notes :sn)]
      (is (= [{:drum :sn :note 38 :chan 9}]
             (map :value (p/query (:pattern (:d (pl/current-voices))) [0 1])))))))

;; ---- program change and CC ---------------------------------------------

(deftest program-and-cc-are-noops-with-no-transport
  (testing "like `panic`, they do nothing rather than throw when stopped"
    (is (= 18 (pl/program! 0 18)))
    (is (= 64 (pl/cc! 0 74 64)))))

(deftest program-and-cc-reach-the-sink
  (let [sink  (m/recording-sink)
        trans (clk/start! {:sink sink :voices-fn (constantly {}) :bpm 600})]
    (try
      ;; Install it the way `begin!` would. Reaching the private atom
      ;; beats widening the public API for a test's convenience.
      (reset! @#'pl/!transport trans)
      (pl/program! 0 18)
      (pl/cc! 3 74 64)
      (let [specs (map :spec (m/log sink))]
        (is (some #{{:type :program :chan 0 :program 18}} specs)
            "the program change was sent")
        (is (some #{{:type :cc :chan 3 :cc 74 :val 64}} specs)
            "the control change was sent"))
      (finally
        (reset! @#'pl/!transport nil)
        (clk/stop! trans)))))
