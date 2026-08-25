(ns mu.tap
  "A bounded, drop-on-full fan-out from the render thread to observers.

  The render thread may allocate, so it may publish; the dispatch thread
  may not, so it never does. Publishing uses `.offer` with no timeout:
  an observer that stops draining loses cycles, and the transport does
  not notice. That direction is deliberate -- the performance outranks
  the display."
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private CAPACITY
  "Cycles buffered per observer. At one publish per cycle, 64 is several
  seconds of slack at any sane tempo -- long enough to ride out a GC
  pause or a slow socket write, short enough that a dead observer does
  not hold megabytes of rendered events alive."
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
  (doseq [^Tap t @!taps]
    (when-not (.offer ^ArrayBlockingQueue (.-q t) v)
      (.incrementAndGet ^AtomicLong (.-drops t))))
  nil)

(defn poll!
  "Take the next value, waiting up to `timeout-ms`. nil if none arrives."
  [^Tap t timeout-ms]
  (.poll ^ArrayBlockingQueue (.-q t) timeout-ms TimeUnit/MILLISECONDS))

(defn dropped [^Tap t] (.get ^AtomicLong (.-drops t)))

(defn reset-all! [] (reset! !taps #{}) nil)
