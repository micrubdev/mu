(ns mu.web.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mu.web.protocol :as proto]))

(def sample-cycle
  {:cycle 128 :t0 123456789012345 :npc 500000000
   :events [{:at 123456789012345
             :spec {:type :note-on :chan 0 :note 36 :vel 0.8}}
            {:at 123456789512345
             :spec {:type :note-off :chan 0 :note 36}}]})

(def sample-state
  {:playing? true :bpm 120
   :voices {:bass {:chan 0 :muted? false :soloed? false :error nil}}})

(deftest cycle-msg-has-the-wire-shape
  (let [m (proto/cycle-msg sample-cycle sample-state)]
    (is (= "cycle" (:t m)))
    (is (= 128 (:n m)))
    (is (= "123456789012345" (:t0 m)) "absolute instants are strings")
    (is (= 500000000 (:npc m)))
    (is (= 120 (:bpm m)) "tempo rides along so the HUD needs no second source")
    (is (= {:chan 0 :muted false :soloed false :error nil}
           (get-in m [:voices :bass]))
        "booleans lose the question mark on the wire")
    (is (= {:d 0 :type "note-on" :chan 0 :note 36 :vel 0.8}
           (first (:msgs m)))
        "the spec is flattened, :type is a string, and the time is a delta")))

(deftest message-times-are-deltas-from-the-cycle-anchor
  (let [m (proto/cycle-msg sample-cycle sample-state)]
    (is (= [0 500000] (map :d (:msgs m)))
        "500000 ns after t0, not an absolute instant")))

(deftest deltas-stay-inside-the-safe-integer-range
  (testing "a delta is bounded by a cycle, so it never approaches 2^53"
    (let [m (proto/cycle-msg sample-cycle sample-state)]
      (is (every? #(< (:d %) 9007199254740992) (:msgs m))))))

(deftest a-note-off-past-the-cycle-end-gets-a-delta-larger-than-npc
  (testing "carried note-offs are legal and must not be clamped"
    (let [c (assoc sample-cycle :events
                   [{:at (+ 123456789012345 900000000)
                     :spec {:type :note-off :chan 0 :note 36}}])
          m (proto/cycle-msg c sample-state)]
      (is (= 900000000 (:d (first (:msgs m)))))
      (is (> (:d (first (:msgs m))) (:npc m))))))

(deftest note-off-messages-carry-no-velocity
  (let [m (proto/cycle-msg sample-cycle sample-state)]
    (is (nil? (:vel (second (:msgs m)))))))

(deftest instants-survive-json-as-exact-longs
  (testing "a string on the wire cannot be rounded by a JSON reader"
    (let [rt (proto/decode (proto/encode (proto/cycle-msg sample-cycle sample-state)))]
      (is (= "123456789012345" (:t0 rt)))
      (is (= 123456789012345 (Long/parseLong (:t0 rt)))))))

(deftest message-order-survives-the-round-trip
  (let [big  (assoc sample-cycle :events
                    (mapv (fn [i] {:at (+ 123456789012345 i)
                                   :spec {:type :note-on :chan 0 :note i :vel 0.5}})
                          (range 200)))
        rt   (proto/decode (proto/encode (proto/cycle-msg big sample-state)))]
    (is (= (range 200) (map :note (:msgs rt))))))

(deftest pong-msg-echoes-the-client-clock
  (let [m (proto/pong-msg 7 12.3456 999)]
    (is (= {:t "pong" :id 7 :c 12.3456 :s "999"} m))))

(deftest decode-rejects-garbage-without-throwing
  (is (nil? (proto/decode "not json {{{"))))

(defspec deltas-round-trip-exactly 100
  (prop/for-all [ds (gen/vector (gen/large-integer* {:min 0 :max 4000000000}) 0 20)]
    (let [t0 123456789012345
          c  {:cycle 0 :t0 t0 :npc 500000000
              :events (mapv (fn [d] {:at (+ t0 d)
                                     :spec {:type :note-on :chan 0
                                            :note 60 :vel 0.5}}) ds)}
          rt (proto/decode (proto/encode (proto/cycle-msg c sample-state)))]
      (= ds (map :d (:msgs rt))))))
