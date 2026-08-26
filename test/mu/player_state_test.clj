(ns mu.player-state-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mu.clock :as clk]
            [mu.midi :as m]
            [mu.notation :refer [notes]]
            [mu.pattern :as p]
            [mu.player :as pl]))

(use-fixtures :each (fn [f] (pl/reset-all!) (f) (pl/reset-all!)))

(def boom
  "A pattern that throws when queried."
  (p/pat (fn [_] (throw (ex-info "boom" {})))))

(deftest state-reports-registered-voices
  (pl/play! :bass (notes c2) {:chan 3})
  (let [s (pl/state)]
    (is (= #{:bass} (set (keys (:voices s)))))
    (is (= 3 (get-in s [:voices :bass :chan])))
    (is (false? (get-in s [:voices :bass :muted?])))
    (is (nil? (get-in s [:voices :bass :error])))))

(deftest state-reports-mute-and-solo
  (pl/play! :a (notes c2))
  (pl/play! :b (notes c3))
  (pl/mute :a)
  (pl/solo :b)
  (let [s (pl/state)]
    (is (true? (get-in s [:voices :a :muted?])))
    (is (true? (get-in s [:voices :b :soloed?])))))

(deftest state-reports-not-playing-without-a-transport
  (is (false? (:playing? (pl/state)))))

(deftest a-throwing-voice-records-its-error
  (pl/play! :bad boom)
  (pl/safe-render :bad 0)
  (is (re-find #"boom" (get-in (pl/state) [:voices :bad :error]))))

(deftest a-recovered-voice-clears-its-error
  (pl/play! :bad boom)
  (pl/safe-render :bad 0)
  (is (some? (get-in (pl/state) [:voices :bad :error])))
  (testing "redefining the voice to something that works clears the mark"
    (pl/play! :bad (notes c2))
    (pl/safe-render :bad 1)
    (is (nil? (get-in (pl/state) [:voices :bad :error])))))

(deftest stopping-a-voice-clears-its-error
  (pl/play! :bad boom)
  (pl/safe-render :bad 0)
  (is (some? (get-in (pl/state) [:voices :bad :error])))
  (testing "stopping the voice clears the error from the registry"
    (pl/stop-voice! :bad)
    (pl/play! :bad (notes c2))
    (is (nil? (get-in (pl/state) [:voices :bad :error])))))

(deftest hush-clears-all-errors
  (pl/play! :bad boom)
  (pl/safe-render :bad 0)
  (is (some? (get-in (pl/state) [:voices :bad :error])))
  (testing "hush clears all errors"
    (pl/hush)
    (pl/play! :bad (notes c2))
    (is (nil? (get-in (pl/state) [:voices :bad :error])))))

(deftest reset-all-clears-all-errors
  (pl/play! :bad boom)
  (pl/safe-render :bad 0)
  (is (some? (get-in (pl/state) [:voices :bad :error])))
  (testing "reset-all! clears all errors and re-register to verify"
    (pl/reset-all!)
    (pl/play! :bad (notes c2))
    (is (nil? (get-in (pl/state) [:voices :bad :error])))))

;; End-to-end: a throwing voice on a REAL running transport (real render
;; and dispatch threads, not a direct render-cycle/safe-render call). This
;; is the path the HUD actually depends on -- mu.clock reaches patterns
;; through voice-messages, not through mu.player/safe-render, so unless
;; the render seam is actually wired up, this fails even though the
;; direct safe-render tests above pass.
(deftest a-throwing-voice-on-a-real-transport-does-not-silence-the-set
  (pl/play! :good (notes c4) {:chan 0})
  (pl/play! :bad  boom       {:chan 0})
  (let [sink  (m/recording-sink)
        trans (clk/start! {:sink sink
                           :voices-fn pl/current-voices
                           :render-voice pl/safe-render
                           :bpm 480})]   ; 4 cycles/sec -- fast, for a short test
    (Thread/sleep 800)
    (let [ons-before (count (filter #(= :note-on (:type (:spec %))) (m/log sink)))]
      (testing "the transport kept producing cycles for the healthy voice"
        (is (pos? ons-before) "expected the healthy voice to have sounded already"))
      (testing "the error is visible in player/state"
        (is (re-find #"boom" (get-in (pl/state) [:voices :bad :error]))))
      (Thread/sleep 800)
      (testing "cycles keep coming after the throw -- the render thread survived"
        (is (< ons-before (count (filter #(= :note-on (:type (:spec %))) (m/log sink))))
            "expected more notes from the healthy voice after the bad one threw"))
      (testing "a recovered pattern clears the error on the next cycle"
        (pl/play! :bad (notes d4) {:chan 0})
        (Thread/sleep 500)
        (is (nil? (get-in (pl/state) [:voices :bad :error])))))
    (clk/stop! trans)))
