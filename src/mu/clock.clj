(ns mu.clock
  "Cycle rendering and the two-thread transport.

  `render-cycle` is pure and thread-free: all the scheduling logic is
  testable without starting anything. The threads in Task 14 wrap it."
  (:require [mu.midi :as midi]
            [mu.pattern :as p])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [java.util.concurrent.locks LockSupport]))

(defn- voice-messages
  "Message specs for one voice over one cycle, as
  [{:at-cycle rational :spec m}]. Note-offs may fall past cycle-end."
  [cycle-n {:keys [pattern chan]}]
  (let [chan (or chan 0)]
    (->> (p/query pattern [cycle-n (inc cycle-n)])
         (filter p/onset?)
         (mapcat
           (fn [{:keys [whole value]}]
             (let [note (:note value)
                   vel  (get value :vel 0.8)
                   ch   (get value :chan chan)]
               (when note
                 [{:at-cycle (first whole)
                   :spec {:type :note-on :chan ch :note note :vel vel}}
                  {:at-cycle (second whole)
                   :spec {:type :note-off :chan ch :note note}}])))))))

(defn render-cycle
  "Render one cycle into flat, pre-sorted, pre-encoded arrays.

  Runs on the render thread and may allocate freely. Returns
  {:times ^longs :ats ^objects :msgs ^objects :specs ^objects :n long
   :carry [...]} where `carry` is the note-offs landing after this cycle
  ends -- they must be passed as `carry-in` to the next render or long
  notes hang.

  `:specs` holds the readable spec maps parallel to the encoded `:msgs`,
  because an encoded device message is opaque and the caller still needs
  to know what it was in order to track sounding notes.

  `:ats` holds the same instants as `:times`, pre-boxed. `:times` is what
  the dispatch loop compares against nanoTime; `:ats` is what it hands to
  `midi/emit!`, whose protocol signature takes an Object. Boxing there
  would allocate one Long per message on the thread that must not
  allocate, so the boxing happens here instead."
  [sink voices cycle-n cycle-start-nanos nanos-per-cycle carry-in]
  (let [cycle-end (inc cycle-n)
        produced  (mapcat (fn [[_k v]] (voice-messages cycle-n v)) voices)
        all       (concat carry-in produced)
        ;; A note-off exactly at the boundary belongs to this cycle;
        ;; anything strictly past it is carried.
        now-msgs  (filter #(<= (:at-cycle %) cycle-end) all)
        later     (filter #(>  (:at-cycle %) cycle-end) all)
        sorted    (sort-by :at-cycle now-msgs)
        n         (count sorted)
        times     (long-array n)
        ats       (object-array n)
        msgs      (object-array n)
        specs     (object-array n)]
    (dorun
      (map-indexed
        (fn [i {:keys [at-cycle spec]}]
          ;; Times are relative to THIS cycle's start, not a global origin.
          ;; That is what lets a tempo change re-anchor cleanly: the caller
          ;; advances the anchor by the current cycle length.
          (let [at (long (+ cycle-start-nanos
                            (* (double (- at-cycle cycle-n))
                               nanos-per-cycle)))]
            (aset-long times i at)
            (aset ats i at))
          (aset specs i spec)
          (aset msgs i (midi/encode sink spec)))
        sorted))
    {:times times :ats ats :msgs msgs :specs specs :n n :carry (vec later)}))

;; ---- transport --------------------------------------------------------

;; ^:const, not ^long: a ^long tag on a def names the clojure.core/long
;; FUNCTION as a class hint and fails to compile. :const inlines the value
;; at each use site as a primitive long literal, which is what the
;; arithmetic in the dispatch loop wants anyway.
(def ^:private ^:const SPIN-NS 1500000)   ; start spinning 1.5ms out

(def ^:private ^:const PACE-HOP-NS 50000000)   ; 50ms, so stop! is prompt

(def ^:private ^:const START-LEAD-NS 250000000)
;; 250 ms. A FIXED wall-clock margin, deliberately not a multiple of the
;; cycle length: the lead only has to cover JIT warmup and the first
;; render, which do not get slower at slow tempos. Scaling it by tempo
;; would mean an eight-second wait before the first note at 60 bpm, and
;; too little margin to be worth anything at 480.

(defn- bpm->nanos-per-cycle
  "One cycle is one bar of 4/4, so a cycle is four beats."
  ^long [bpm]
  (long (/ (* 4 60 1e9) (double bpm))))

(defn- wait-until!
  "Park until `target` nanos, in short hops so stop! stays responsive.

  Render-thread only: unlike the dispatch loop this may allocate and
  need not be sample-accurate -- it is throttling cycle production, not
  placing a note."
  [^long target running?]
  (loop []
    (let [remaining (- target (System/nanoTime))]
      (when (and (pos? remaining) @running?)
        (LockSupport/parkNanos (min remaining PACE-HOP-NS))
        (recur)))))

(defn- dispatch-cycle!
  "Walk one rendered cycle, sending each message at its instant.

  MUST NOT ALLOCATE. No seqs, no boxing, no object construction --
  everything here is primitive array access and protocol calls. The
  timestamp handed to emit! comes pre-boxed from `ats` for that reason.

  `n` is unboxed into a local rather than hinted in the parameter list:
  a fn with any primitive-hinted argument is capped at four arguments,
  and this one takes six. The local binding gets the same primitive
  comparison in the loop."
  [sink ^longs times ^objects ats ^objects msgs n running?]
  (loop [i 0, n (long n)]
    (when (and (< i n) @running?)
      (let [target (aget times i)]
        (loop []
          (let [now (System/nanoTime)]
            (when (< now (- target SPIN-NS))
              (LockSupport/parkNanos (- target now SPIN-NS))
              (recur))))
        (while (< (System/nanoTime) target) (Thread/onSpinWait))
        (midi/emit! sink (aget msgs i) (aget ats i)))
      (recur (unchecked-inc i) n))))

(defn- note-key ^long [chan note] (+ (* 128 (long chan)) (long note)))

(defn- track-active!
  "Maintain the sounding-note table so panic! and stop! are exact."
  [^booleans active spec]
  (case (:type spec)
    :note-on  (aset-boolean active (note-key (:chan spec) (:note spec)) true)
    :note-off (aset-boolean active (note-key (:chan spec) (:note spec)) false)
    nil))

(defn- silence-all!
  "Note-off every note the table says is sounding, then all-notes-off
  on all sixteen channels as a belt-and-braces measure."
  [sink ^booleans active]
  (dotimes [k (* 16 128)]
    (when (aget active k)
      (midi/emit! sink
                  (midi/encode sink {:type :note-off :chan (quot k 128) :note (rem k 128)})
                  (System/nanoTime))
      (aset-boolean active k false)))
  (dotimes [ch 16]
    (midi/emit! sink
                (midi/encode sink {:type :cc :chan ch :cc 123 :val 0})
                (System/nanoTime))))

(defn start!
  "Start the transport. Returns a map to pass to stop!/panic!/set-bpm!.

  `voices-fn` is called once per cycle on the render thread; whatever
  it returns is what plays next cycle. That poll is what makes var
  redefinition land on a cycle boundary."
  [{:keys [sink voices-fn bpm] :or {bpm 120}}]
  (let [running? (atom true)
        !bpm     (atom bpm)
        active   (boolean-array (* 16 128))
        q        (ArrayBlockingQueue. 2)
        t0       (+ (System/nanoTime) START-LEAD-NS)

        render
        (Thread.
          (fn []
            ;; Warmup: render a silent cycle so the JIT has compiled
            ;; this path before the first audible note.
            (render-cycle sink {} 0 t0 (bpm->nanos-per-cycle @!bpm) [])
            (loop [cyc 0, anchor t0, carry []]
              (when @running?
                (let [;; Re-read tempo every cycle. Because event times are
                      ;; relative to `anchor`, and `anchor` advances by the
                      ;; CURRENT cycle length, a tempo change takes effect at
                      ;; the next boundary with no discontinuity.
                      npc (bpm->nanos-per-cycle @!bpm)
                      ;; Pace against the wall clock before rendering.
                      ;; The only other backpressure is the dispatch
                      ;; thread waiting on message times, and a cycle
                      ;; with no messages provides none -- so a stretch
                      ;; of silence (a transport started before any
                      ;; voice is registered, a long hush) would let
                      ;; this loop spin at CPU speed, carrying `anchor`
                      ;; hours into the future. Nothing registered after
                      ;; that would ever sound.
                      _   (wait-until! (- anchor npc) running?)
                      vs  (try (voices-fn)
                               (catch Throwable t
                                 (println "mu: voices-fn threw:" (.getMessage t))
                                 {}))
                      r   (render-cycle sink vs cyc anchor npc carry)
                      n   (long (:n r))]
                  ;; Track from :specs, NOT :msgs. An encoded message is
                  ;; opaque, so reading :type off it would silently no-op for
                  ;; every real sink -- passing tests against the recording
                  ;; sink while leaving notes untracked in production.
                  (dotimes [i n]
                    (track-active! active (aget ^objects (:specs r) i)))
                  ;; Offer with a timeout rather than a blocking put, so a
                  ;; stopped dispatch thread cannot wedge the renderer.
                  (loop []
                    (when (and @running?
                               (not (.offer q [(:times r) (:ats r) (:msgs r) n]
                                            500 TimeUnit/MILLISECONDS)))
                      (recur)))
                  (recur (inc cyc) (+ anchor npc) (:carry r)))))))

        dispatch
        (Thread.
          (fn []
            ;; Warmup: exercise the spin loop before it matters.
            (dotimes [_ 2000]
              (let [t (System/nanoTime)] (while (< (System/nanoTime) (+ t 1000)) nil)))
            (while @running?
              (when-let [[times ats msgs n] (.poll q 200 TimeUnit/MILLISECONDS)]
                (dispatch-cycle! sink times ats msgs n running?)))))

        transport {:running? running? :bpm !bpm :active active
                   :sink sink :render render :dispatch dispatch}]
    (.setDaemon render true)
    (.setDaemon dispatch true)
    (.setPriority dispatch Thread/MAX_PRIORITY)
    (.start render)
    (.start dispatch)
    transport))

(defn panic!
  "Silence everything immediately, leaving the transport running."
  [{:keys [sink active]}]
  (silence-all! sink active))

(defn stop!
  "Stop the transport and silence every sounding note."
  [{:keys [running? sink active] :as transport}]
  (reset! running? false)
  (silence-all! sink active)
  transport)

(defn set-bpm!
  "Change tempo. Takes effect at the next cycle boundary."
  [{:keys [bpm]} n]
  (reset! bpm n))
