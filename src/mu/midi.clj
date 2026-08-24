(ns mu.midi
  "MIDI output: the MidiSink seam, message encoding, and a recording
  sink for tests.

  The protocol is deliberately two-phase. `encode` runs on the RENDER
  thread and may allocate; `emit!` runs on the DISPATCH thread and must
  not. Constructing a javax ShortMessage per note inside the dispatch
  loop would violate the no-allocation rule, so messages are built one
  cycle ahead and only handed to the device at play time.

  This seam is load-bearing rather than speculative: measurement showed
  the residual timing tail is OS scheduling, which only a timestamped
  native sink can remove. Do not collapse encode and emit!.")

(defprotocol MidiSink
  (encode [sink spec]
    "Render thread. Turn a message spec map into an opaque device
     message. May allocate.")
  (emit! [sink encoded at-nanos]
    "Dispatch thread. Deliver an already-encoded message. MUST NOT
     allocate. `at-nanos` is the intended time (a boxed Long), for sinks
     that can schedule; approach-A sinks send immediately and ignore it.

     `at-nanos` is deliberately NOT primitive-hinted: Clojure protocol
     methods take Object arguments only, and a ^long hint here compiles
     the call site to an IFn$OOLO invocation that no protocol
     implementation can satisfy. To keep the no-allocation rule, the
     render thread pre-boxes the timestamps and dispatch passes the
     boxed value straight through -- see mu.clock/render-cycle :ats.")
  (close-sink! [sink]
    "Release the device."))

(defn vel->midi
  "0.0-1.0 double, or a raw 0-127 integer, to a clamped MIDI velocity."
  ^long [v]
  (let [raw (if (integer? v) (long v) (Math/round (* 127.0 (double v))))]
    (max 0 (min 127 raw))))

;; ---- recording sink (tests) -------------------------------------------

(defrecord RecordingSink [!log]
  MidiSink
  (encode [_ spec] spec)
  (emit! [_ encoded at-nanos] (swap! !log conj {:at at-nanos :spec encoded}))
  (close-sink! [_] nil))

(defn recording-sink
  "A sink that records every emission instead of making sound."
  []
  (->RecordingSink (atom [])))

(defn log
  "Everything a recording sink has been asked to emit, in order."
  [sink]
  @(:!log sink))
