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

(deftest transport-plays-and-stops
  (let [sink  (m/recording-sink)
        trans (clk/start! {:sink sink
                           :voices-fn (constantly {:v {:pattern (notes c4 d4) :chan 0}})
                           :bpm 480})]   ; 4 cycles/sec -- fast, for a short test
    (Thread/sleep 1500)
    (clk/stop! trans)
    (let [ons (filter #(= :note-on (:type (:spec %))) (m/log sink))]
      (is (< 3 (count ons)) (str "expected several notes, got " (count ons))))))

(deftest stop-silences-every-sounding-note
  (let [sink  (m/recording-sink)
        trans (clk/start! {:sink sink
                           :voices-fn (constantly {:v {:pattern (p/slow 4 (notes c4))
                                                       :chan 0}})
                           :bpm 480})]
    (Thread/sleep 800)
    (clk/stop! trans)
    (let [l    (m/log sink)
          ons  (filter #(= :note-on  (:type (:spec %))) l)
          offs (filter #(= :note-off (:type (:spec %))) l)]
      (is (pos? (count ons)))
      (is (>= (count offs) (count ons))
          "every note that started was explicitly stopped"))))

(deftest panic-sends-all-notes-off-on-every-channel
  (let [sink  (m/recording-sink)
        trans (clk/start! {:sink sink :voices-fn (constantly {}) :bpm 120})]
    (Thread/sleep 100)
    (clk/panic! trans)
    ;; Snapshot BEFORE stop!, which runs silence-all! again and would
    ;; double the count.
    (let [after-panic (m/log sink)]
      (clk/stop! trans)
      (let [ccs (filter #(and (= :cc (:type (:spec %)))
                              (= 123 (:cc (:spec %))))
                        after-panic)]
        (is (= 16 (count ccs)) "all-notes-off on all sixteen channels")))))

(deftest active-note-tracking-survives-an-opaque-encoding
  ;; Regression guard for a bug the recording sink CANNOT catch: its
  ;; encode is identity, so reading :type off an encoded message happens
  ;; to work. Any real sink returns something opaque. This sink mimics
  ;; that, so tracking must read :specs rather than :msgs.
  (let [emitted (atom [])
        sink    (reify m/MidiSink
                  (encode [_ spec] (str "OPAQUE:" (:type spec)))
                  (emit! [_ enc at] (swap! emitted conj {:at at :enc enc}) nil)
                  (close-sink! [_] nil))
        trans   (clk/start! {:sink sink
                             :voices-fn (constantly
                                          {:v {:pattern (p/slow 4 (notes c4))
                                               :chan 0}})
                             :bpm 480})]
    (Thread/sleep 800)
    (clk/stop! trans)
    (testing "stop! knew a note was sounding and turned it off"
      (is (some #(= "OPAQUE::note-off" (:enc %)) @emitted)
          "an explicit note-off means the active table was populated"))))

(deftest tempo-change-re-anchors-instead-of-jumping
  (let [sink  (m/recording-sink)
        trans (clk/start! {:sink sink
                           :voices-fn (constantly {:v {:pattern (notes c4) :chan 0}})
                           :bpm 480})]
    (Thread/sleep 800)
    (clk/set-bpm! trans 960)
    (Thread/sleep 1500)
    (clk/stop! trans)
    (let [ats (map :at (filter #(= :note-on (:type (:spec %))) (m/log sink)))]
      (is (< 2 (count ats)) "several notes played across the tempo change")
      (is (apply < ats)
          "times stay monotonically increasing -- a re-anchoring bug would
           make future cycles jump backwards or forwards discontinuously"))))

(deftest voices-fn-is-polled-every-cycle
  (let [calls (atom 0)
        sink  (m/recording-sink)
        trans (clk/start! {:sink sink
                           :voices-fn (fn [] (swap! calls inc) {})
                           :bpm 480})]
    (Thread/sleep 900)
    (clk/stop! trans)
    (is (< 2 @calls) (str "expected several polls, got " @calls))))

(deftest the-transport-starts-playing-promptly
  ;; Guards the warm-up lead. It is a fixed wall-clock margin, not a
  ;; multiple of the cycle length: at 60 bpm two cycles would be an
  ;; eight-second wait before the first note.
  (let [sink  (m/recording-sink)
        t-start (System/nanoTime)
        trans (clk/start! {:sink sink
                           :voices-fn (constantly {:v {:pattern (notes c4) :chan 0}})
                           :bpm 60})]   ; 4s per cycle
    (Thread/sleep 1000)
    (clk/stop! trans)
    (let [ons (filter #(= :note-on (:type (:spec %))) (m/log sink))]
      (is (= 1 (count ons)) "the first note sounded well inside one cycle")
      (is (< (- (:at (first ons)) t-start) 1000000000)
          "and did so less than a second after start!"))))

(deftest a-voice-added-after-the-transport-starts-still-plays
  ;; The live-coding path: begin! opens the transport, and play! registers
  ;; a voice some seconds later. Rendering silence must not run the clock
  ;; off into the future.
  (let [sink   (m/recording-sink)
        !voice (atom {})
        trans  (clk/start! {:sink sink :voices-fn (fn [] @!voice) :bpm 480})]
    (Thread/sleep 1000)                                  ; two silent cycles
    (reset! !voice {:v {:pattern (notes c4 d4) :chan 0}})
    (Thread/sleep 1500)
    (clk/stop! trans)
    (let [ons (filter #(= :note-on (:type (:spec %))) (m/log sink))]
      (is (< 2 (count ons))
          (str "expected the late voice to sound, got " (count ons) " notes")))))

(deftest silent-cycles-do-not-run-the-render-thread-away
  ;; Backpressure comes from the dispatch thread waiting on message times,
  ;; so a cycle with no messages provides none. Without wall-clock pacing
  ;; the renderer spins as fast as the CPU allows -- tens of thousands of
  ;; cycles per second -- and the clock's anchor ends up hours ahead.
  (let [calls (atom 0)
        sink  (m/recording-sink)
        trans (clk/start! {:sink sink
                           :voices-fn (fn [] (swap! calls inc) {})
                           :bpm 480})]   ; 0.5s per cycle
    (Thread/sleep 1000)
    (clk/stop! trans)
    (is (< 1 @calls) "the clock is still running")
    (is (< @calls 12)
        (str "one poll per cycle plus a little lookahead, got " @calls))))

(deftest stop-is-synchronous
  ;; `stop!` used to flip `running?` and return without joining. The
  ;; render thread woke from `wait-until!` already PAST the `@running?`
  ;; check, finished one more cycle and reached `tap/publish!`.
  ;; `mu.tap`'s subscriber set is global, so that stray frame landed in
  ;; whatever tap the NEXT namespace had subscribed --
  ;; mu.tap-test/nil-publish-is-a-no-op failed about one run in three.
  ;; mu.clock-tap-test never leaked because it joined by hand.
  (testing "both threads are gone by the time stop! returns"
    (let [trans (clk/start! {:sink (m/recording-sink)
                             :voices-fn (constantly {})
                             :bpm 120})]
      (Thread/sleep 50)
      (clk/stop! trans)
      (is (not (.isAlive ^Thread (:render trans)))
          "render thread outlived stop! and can still publish")
      (is (not (.isAlive ^Thread (:dispatch trans)))
          "dispatch thread outlived stop!"))))
