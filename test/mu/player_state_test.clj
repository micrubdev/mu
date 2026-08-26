(ns mu.player-state-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
