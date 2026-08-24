(ns mu.pattern
  "The pattern algebra. A Pattern is a pure function from a span to the
  events active within it.

  Purity boundary: nothing here reads wall-clock time, touches MIDI, or
  holds mutable state. Everything is testable by calling `query`."
  (:refer-clojure :exclude [rand])
  (:require [mu.time :as t]))

(defrecord Pattern [query])

(defn pat
  "Wrap a query function (span -> seq of events) as a Pattern."
  [f]
  (->Pattern f))

(defn pattern?
  [x]
  (instance? Pattern x))

(defn query
  "Ask a pattern for the events active in a span."
  [p span]
  ((:query p) span))

(defn onset?
  "True when this event fragment contains the start of its own note.

  Only onsets trigger. A query span that bisects a note yields a
  fragment whose part starts after its whole -- without this check the
  note would retrigger mid-sustain. Continuous signals (:whole nil)
  are never onsets."
  [{:keys [whole part]}]
  (boolean (and whole (= (first whole) (first part)))))

(def silence
  (pat (fn [_] ())))

(defn pure
  "A pattern repeating one value, filling every cycle."
  [v]
  (pat (fn [sp]
         (for [[b e] (t/split-cycles sp)
               :let  [c (t/floor-cycle b)]]
           {:whole [c (inc c)] :part [b e] :value v}))))
