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
  native sink can remove. Do not collapse encode and emit!."
  (:require [clojure.string :as str])
  (:import [javax.sound.midi MidiSystem MidiDevice MidiDevice$Info
            Receiver ShortMessage MidiMessage]))

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
     boxed value straight through -- see mu.render/render-cycle :ats.")
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

;; ---- javax sink -------------------------------------------------------

(defn list-ports
  "Every MIDI device this JVM can see that can receive messages.

  On a machine with no ALSA/CoreMIDI this returns only the JDK's
  bundled `Gervill` software synth and `Real Time Sequencer`. That is
  an environment fact, not an error."
  []
  (for [info (MidiSystem/getMidiDeviceInfo)
        :let [dev (MidiSystem/getMidiDevice info)]
        :when (not (zero? (.getMaxReceivers dev)))]
    {:name        (.getName info)
     :description (.getDescription info)
     ;; -1 means "unlimited" in the javax API
     :receivers   (.getMaxReceivers dev)}))

(defn- short-message
  ^ShortMessage [status chan a b]
  (doto (ShortMessage.)
    (.setMessage (int status) (int chan) (int a) (int b))))

(defrecord JavaxSink [^MidiDevice device ^Receiver receiver]
  MidiSink
  (encode [_ {:keys [type chan note vel cc val program]}]
    (let [ch (int (or chan 0))]
      (case type
        :note-on  (short-message ShortMessage/NOTE_ON  ch note (vel->midi vel))
        :note-off (short-message ShortMessage/NOTE_OFF ch note 0)
        :cc       (short-message ShortMessage/CONTROL_CHANGE ch cc val)
        ;; A program change carries ONE data byte; the second is ignored
        ;; and the encoded message is two bytes long, not three.
        :program  (short-message ShortMessage/PROGRAM_CHANGE ch program 0)
        (throw (IllegalArgumentException. (str "unknown message type: " type))))))

  (emit! [_ encoded _at-nanos]
    ;; Approach A: the dispatch thread has already waited until the
    ;; right instant, so send now. -1 means "no timestamp".
    (.send receiver ^MidiMessage encoded -1)
    nil)

  (close-sink! [_]
    (.close receiver)
    (.close device)
    nil))

(defn send-now!
  "Encode `spec` and emit it immediately.

  For messages that are not part of a rendered cycle -- a program
  change, a control change typed at the REPL -- where there is no
  schedule to place them on and `System/nanoTime` is the only sensible
  instant. The render path does NOT go through here: it encodes ahead
  of time so the dispatch thread never allocates."
  [sink spec]
  (emit! sink (encode sink spec) (System/nanoTime))
  nil)

(defn open-sink!
  "Open a MIDI output by case-insensitive substring match on its name.
  With nil, opens the system default receiver's device.

  Throws with the list of available ports if nothing matches -- on a
  machine with no MIDI subsystem that list is the useful part."
  [name-substring]
  (let [ports (list-ports)
        want  (some-> name-substring str/lower-case)
        hit   (if want
                (first (filter #(str/includes? (str/lower-case (:name %)) want)
                               ports))
                (first ports))]
    (when-not hit
      (throw (ex-info (str "No MIDI output matching " (pr-str name-substring)
                           ". Available: "
                           (if (seq ports)
                             (str/join ", " (map :name ports))
                             "(none -- this machine has no MIDI subsystem)"))
                      {:requested name-substring :available (mapv :name ports)})))
    (let [info (first (filter #(= (:name hit) (.getName ^MidiDevice$Info %))
                              (MidiSystem/getMidiDeviceInfo)))
          dev  (MidiSystem/getMidiDevice info)]
      (.open dev)
      (->JavaxSink dev (.getReceiver dev)))))
