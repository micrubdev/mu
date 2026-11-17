(ns mu.web.protocol
  "The wire format. Pure functions -- no sockets, no threads.

  Every instant on the wire is a System/nanoTime nanosecond. That is
  larger than 2^53, which `JSON.parse` would silently round to the nearest
  double -- a timing error no test on either side would notice. So absolute
  instants travel as decimal strings, for BigInt on the client, and each
  message carries its offset from the cycle anchor instead of an absolute
  time. An offset is bounded by a cycle length, so it is always exact as a
  double, and the client adds it back to the anchor in BigInt.

  Carried note-offs legitimately land past the cycle end, so an offset may
  exceed :npc. It must not be clamped."
  (:require [cheshire.core :as json]))

(defn- msg
  "Flatten one rendered event into a wire message, timed as an offset."
  [t0 {:keys [at spec]}]
  (cond-> {:d    (- at t0)
           :type (name (:type spec))
           :chan (:chan spec)
           :note (:note spec)}
    (contains? spec :vel) (assoc :vel (:vel spec))))

(defn- voice
  [[k {:keys [chan muted? soloed? error]}]]
  [k {:chan chan :muted muted? :soloed soloed? :error error}])

(defn- hit
  "Bar fractions are exact rationals server-side; a double is plenty
  for drawing, and JSON has nothing else."
  [h]
  (update h :at double))

(defn- shape [s]
  (when s (update s :hits #(mapv hit %))))

(defn cycle-msg
  "Build the per-cycle broadcast: the events, a full state snapshot, and
  each voice's bar shape for the circle view (see mu.shape). A voice
  with no shape yet is sent as null, not left out."
  ([c player-state] (cycle-msg c player-state {}))
  ([{:keys [cycle t0 npc events]} player-state shapes]
   {:t      "cycle"
    :n      cycle
    :t0     (str t0)
    :npc    npc
    :bpm    (:bpm player-state)
    :voices (into {} (map voice) (:voices player-state))
    :msgs   (mapv (partial msg t0) events)
    :shapes (into {} (map (fn [[k s]] [k (shape s)])) shapes)}))

(defn pong-msg
  "Reply to a clock-sync ping: echo the client's own clock, add ours."
  [id client-time server-nanos]
  {:t "pong" :id id :c client-time :s (str server-nanos)})

(defn encode ^String [m] (json/generate-string m))

(defn decode
  "Parse a client frame. Returns nil rather than throwing on garbage --
  a browser can send anything, and a malformed frame must not take down
  the connection."
  [^String s]
  (try (json/parse-string s true)
       (catch Exception _ nil)))
