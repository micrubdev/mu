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

(deftest list-ports-returns-descriptors
  (let [ports (m/list-ports)]
    (is (sequential? ports))
    (testing "every entry is well-formed"
      (doseq [p ports]
        (is (string? (:name p)))
        (is (string? (:description p)))
        (is (integer? (:receivers p)))))
    (testing "the JDK's bundled software synth is always present"
      ;; True on any JDK, with or without hardware MIDI. This is the
      ;; only port assertion that is safe in CI.
      (is (some #(= "Gervill" (:name %)) ports)))))

(deftest opening-an-unknown-port-fails-loudly
  (let [e (try (m/open-sink! "definitely-no-such-port-xyzzy")
               nil
               (catch Exception e e))]
    (is (some? e) "must throw rather than return nil")
    (is (re-find #"definitely-no-such-port-xyzzy" (.getMessage e))
        "the message names what was asked for")
    (is (re-find #"Gervill" (.getMessage e))
        "and lists what is actually available")))

(deftest encoding-produces-correct-midi-bytes
  ;; Spec section 9 requires byte-level tests. Status nibbles: note-on
  ;; 0x90, note-off 0x80, control-change 0xB0, each OR'd with the channel.
  (let [sink (m/open-sink! "Gervill")
        bytes-of (fn [spec] (vec (.getMessage ^javax.sound.midi.MidiMessage
                                              (m/encode sink spec))))]
    (try
      (testing "note-on, channel 0"
        (is (= [(unchecked-byte 0x90) (byte 60) (byte 100)]
               (bytes-of {:type :note-on :chan 0 :note 60 :vel 100}))))
      (testing "channel is in the low nibble of the status byte"
        (is (= [(unchecked-byte 0x93) (byte 60) (byte 100)]
               (bytes-of {:type :note-on :chan 3 :note 60 :vel 100}))))
      (testing "note-off always carries velocity 0"
        (is (= [(unchecked-byte 0x80) (byte 60) (byte 0)]
               (bytes-of {:type :note-off :chan 0 :note 60}))))
      (testing "control change"
        (is (= [(unchecked-byte 0xB0) (byte 123) (byte 0)]
               (bytes-of {:type :cc :chan 0 :cc 123 :val 0}))))
      (testing "a 0.0-1.0 velocity is scaled into the byte"
        (is (= (byte 127) (nth (bytes-of {:type :note-on :chan 0 :note 60 :vel 1.0}) 2)))
        (is (= (byte 64)  (nth (bytes-of {:type :note-on :chan 0 :note 60 :vel 0.5}) 2))))
      (testing "program change is two bytes -- it carries no second data byte"
        (is (= [(unchecked-byte 0xC0) (byte 18)]
               (bytes-of {:type :program :chan 0 :program 18}))))
      (testing "program change puts the channel in the low nibble too"
        (is (= [(unchecked-byte 0xC5) (byte 18)]
               (bytes-of {:type :program :chan 5 :program 18}))))
      (testing "an unknown type is rejected rather than silently dropped"
        (is (thrown? IllegalArgumentException (bytes-of {:type :nonsense :chan 0}))))
      (finally (m/close-sink! sink)))))

(deftest javax-sink-round-trips-a-note
  ;; Gervill accepts messages on any JDK; this exercises encode/emit!
  ;; without requiring hardware.
  (let [sink (m/open-sink! "Gervill")]
    (try
      (let [on  (m/encode sink {:type :note-on  :chan 0 :note 60 :vel 100})
            off (m/encode sink {:type :note-off :chan 0 :note 60})]
        (is (instance? javax.sound.midi.MidiMessage on))
        (is (nil? (m/emit! sink on  (System/nanoTime))))
        (is (nil? (m/emit! sink off (System/nanoTime)))))
      (finally (m/close-sink! sink)))))

(deftest send-now-encodes-and-emits-in-one-step
  ;; A recording sink needs no audio line, so unlike the byte-level tests
  ;; above this one runs on a headless machine.
  (let [sink (m/recording-sink)]
    (m/send-now! sink {:type :program :chan 2 :program 80})
    (m/send-now! sink {:type :cc :chan 2 :cc 74 :val 64})
    (is (= [{:type :program :chan 2 :program 80}
            {:type :cc :chan 2 :cc 74 :val 64}]
           (map :spec (m/log sink)))
        "both messages reached the sink, in order")
    (is (every? some? (map :at (m/log sink)))
        "each carries an emission time")))

(deftest program-change-bytes-without-a-device
  ;; `JavaxSink/encode` is pure -- it builds a ShortMessage and never
  ;; touches the receiver -- so the byte layout can be asserted on a
  ;; machine with no audio line, unlike the round-trip test above.
  (let [sink     (m/->JavaxSink nil nil)
        bytes-of (fn [spec] (vec (.getMessage ^javax.sound.midi.MidiMessage
                                              (m/encode sink spec))))]
    (testing "two bytes: status|channel, then the program"
      (is (= [(unchecked-byte 0xC0) (byte 18)]
             (bytes-of {:type :program :chan 0 :program 18}))))
    (testing "the channel sits in the low nibble"
      (is (= [(unchecked-byte 0xC5) (byte 18)]
             (bytes-of {:type :program :chan 5 :program 18}))))
    (testing "program 0 is a legal patch, not a missing one"
      (is (= [(unchecked-byte 0xC9) (byte 0)]
             (bytes-of {:type :program :chan 9 :program 0}))))
    (testing "a control change is still three bytes"
      (is (= [(unchecked-byte 0xB0) (byte 123) (byte 0)]
             (bytes-of {:type :cc :chan 0 :cc 123 :val 0}))))))
