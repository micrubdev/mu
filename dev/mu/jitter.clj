(ns mu.jitter
  "Timing acceptance harness. Spec section 8: p99 <= 1 ms.

  Ported from the throwaway spike, but measuring the REAL clock through
  a timestamping sink rather than a synthetic loop.

  Interpreting results: the spike showed the residual tail is OS
  scheduling, not GC -- a no-churn control had the same p999 as a
  heavy-GC run. This machine is a lower bound. Re-run on the target
  performance machine before concluding anything about fitness."
  (:require [mu.clock :as clk]
            [mu.midi :as midi]
            [mu.notation :refer [notes]]
            [mu.pattern :as p]
            [mu.tap]))

(defrecord TimestampingSink [!log]
  midi/MidiSink
  (encode [_ spec] spec)
  (emit! [_ _encoded at-nanos]
    ;; Record intended vs actual. This allocates, which is fine: the
    ;; harness measures the wait, not a production send path.
    (swap! !log conj (- (System/nanoTime) (long at-nanos))))
  (close-sink! [_] nil))

(defn- pct [sorted q]
  (/ (nth sorted (min (dec (count sorted))
                      (int (* q (count sorted)))))
     1e6))

(defn measure
  "Run the clock for `seconds` at `bpm` with `steps` notes per cycle.
  Returns deviation percentiles in milliseconds."
  [{:keys [bpm steps seconds] :or {bpm 120 steps 16 seconds 60}}]
  (let [!log  (atom [])
        sink  (->TimestampingSink !log)
        pat   (p/fast steps (notes c4))
        trans (clk/start! {:sink sink
                           :voices-fn (constantly {:v {:pattern pat :chan 0}})
                           :bpm bpm})]
    (Thread/sleep (* 1000 seconds))
    (clk/stop! trans)
    (let [s (vec (sort @!log))]
      (when (empty? s)
        (throw (ex-info "no events captured -- did the clock start?" {})))
      {:n    (count s)
       :p50  (pct s 0.50)
       :p99  (pct s 0.99)
       :p999 (pct s 0.999)
       :max  (/ (peek s) 1e6)})))

(defn -main [& _]
  (println "mu jitter harness -- 16ths @120bpm, 60s. Target: p99 <= 1.0 ms")
  (let [r (measure {:bpm 120 :steps 16 :seconds 60})]
    (println (format "  n=%d  p50=%.3fms  p99=%.3fms  p999=%.3fms  max=%.3fms"
                     (:n r) (:p50 r) (:p99 r) (:p999 r) (:max r)))
    (println (if (<= (:p99 r) 1.0)
               "  PASS -- meets the spec section 8 target on this machine."
               "  FAIL -- p99 over budget. Check ZGC is on; then see spec section 8."))))

(defn -main-tapped [& _]
  (let [tp (mu.tap/subscribe!)
        drain (doto (Thread. #(while true (mu.tap/poll! tp 100)))
                (.setDaemon true) (.start))]
    (-main)))
