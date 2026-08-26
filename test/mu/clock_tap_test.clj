(ns mu.clock-tap-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mu.clock :as clk]
            [mu.midi :as m]
            [mu.notation :refer [notes]]
            [mu.tap :as tap]))

(use-fixtures :each (fn [f] (tap/reset-all!) (f) (tap/reset-all!)))

(defn- with-transport
  "Start a transport over a recording sink, run `f` with it, always stop.

  `stop!` flips `running?` but does not join the render thread, so a
  cycle already past that check can still finish rendering and publish
  after `stop!` returns. `mu.tap`'s subscriber set is global, so a
  stray publish like that can land in the *next* test's freshly
  subscribed tap. Joining here (bounded, so a hang shows up as a test
  failure rather than an infinite wait) makes sure the render thread
  is fully gone before this test's tap goes out of scope."
  [voices f]
  (let [t (clk/start! {:sink (m/recording-sink)
                       :voices-fn (constantly voices)
                       :bpm 480})]           ; fast cycles keep the test short
    (try (f t)
         (finally (clk/stop! t)
                  (.join ^Thread (:render t) 2000)))))

(deftest a-subscriber-sees-rendered-cycles
  (let [tp (tap/subscribe!)]
    (with-transport {:v {:pattern (notes c4 e4) :chan 0}}
      (fn [_]
        (let [c (tap/poll! tp 2000)]
          (is (some? c) "a cycle arrived within 2s")
          (is (int? (:cycle c)))
          (is (int? (:t0 c)))
          (is (pos? (:npc c)))
          (testing "events carry readable specs, not encoded messages"
            (let [types (map (comp :type :spec) (:events c))]
              ;; :events is documented as already time-ordered, so the
              ;; expected sequence is checked directly. (The brief's
              ;; `(sort-by identity types)` cannot pass for any
              ;; implementation: keyword compare puts :note-off before
              ;; :note-on alphabetically, so a sorted 2-on/2-off sequence
              ;; is always (:note-off :note-off :note-on :note-on), never
              ;; the alternating on/off/on/off below.)
              (is (= [:note-on :note-off :note-on :note-off] types)
                  "two onsets produce two note-on/note-off pairs")
              (is (= 60 (-> c :events first :spec :note))))))))))

(deftest events-are-in-time-order
  (let [tp (tap/subscribe!)]
    (with-transport {:v {:pattern (notes c4 e4 g4) :chan 0}}
      (fn [_]
        (let [c (tap/poll! tp 2000)
              ats (map :at (:events c))]
          (is (= ats (sort ats))))))))

(deftest cycle-numbers-advance
  (let [tp (tap/subscribe!)]
    (with-transport {:v {:pattern (notes c4) :chan 0}}
      (fn [_]
        (let [a (tap/poll! tp 2000)
              b (tap/poll! tp 2000)]
          (is (= (inc (:cycle a)) (:cycle b))))))))

(deftest a-transport-with-no-subscribers-still-runs
  (testing "publishing is skipped entirely when nobody is listening"
    (is (false? (tap/any?)))
    (with-transport {:v {:pattern (notes c4) :chan 0}}
      (fn [t] (is (true? @(:running? t)))))))
