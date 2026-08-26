(ns mu.tap
  "A bounded, drop-on-full fan-out from the render thread to observers.

  The render thread may allocate, so it may publish; the dispatch thread
  may not, so it never does. Publishing uses `.offer` with no timeout:
  an observer that stops draining loses cycles, and the transport does
  not notice. That direction is deliberate -- the performance outranks
  the display."
  (:refer-clojure :exclude [any?])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private CAPACITY
  "Cycles buffered per observer. At one publish per cycle, 64 is several
  seconds of slack at any sane tempo -- long enough to ride out a GC
  pause or a slow socket write, short enough that a dead observer does
  not hold megabytes of rendered events alive.

  Retention scales with events per cycle, not just cycle count: a cycle
  with many onsets holds more live objects per slot than a sparse one, so
  a dense pattern makes each buffered cycle heavier even at the same
  depth of 64."
  64)

(defrecord Tap [^ArrayBlockingQueue q ^AtomicLong drops])

(defonce ^:private !taps (atom #{}))

(defn any? [] (boolean (seq @!taps)))

(defn subscribe! []
  (let [t (->Tap (ArrayBlockingQueue. CAPACITY) (AtomicLong. 0))]
    (swap! !taps conj t)
    t))

(defn unsubscribe! [t]
  (swap! !taps disj t)
  nil)

(defn publish!
  "Offer `v` to every subscriber. Never blocks, never throws."
  [v]
  ;; Guard against nil to prevent ArrayBlockingQueue.offer() throwing
  ;; NullPointerException. The render thread must never crash; a bad value
  ;; just vanishes here rather than taking down the transport.
  (when-not (nil? v)
    (doseq [^Tap t @!taps]
      (when-not (.offer ^ArrayBlockingQueue (.-q t) v)
        (.incrementAndGet ^AtomicLong (.-drops t)))))
  nil)

(defn poll!
  "Take the next value, waiting up to `timeout-ms`. nil if none arrives."
  [^Tap t timeout-ms]
  (.poll ^ArrayBlockingQueue (.-q t) timeout-ms TimeUnit/MILLISECONDS))

(defn dropped [^Tap t] (.get ^AtomicLong (.-drops t)))

(defn reset-all!
  "Drop every subscriber without unsubscribing them.

  A test hook, not a performance control: it does not close or drain
  existing Tap instances, so a caller still holding one orphans it --
  every subsequent publish! silently stops reaching it. Tests use this
  between runs because nothing in a test holds a Tap across it; a
  performer calling this mid-set would silently kill every live
  broadcaster (the HUD included)."
  []
  (reset! !taps #{})
  nil)
