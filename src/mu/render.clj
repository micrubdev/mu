(ns mu.render
  "Pure cycle rendering: patterns in, a flat sorted schedule out.

  Nothing here starts a thread or reads a clock, so every scheduling
  decision is testable by calling `render-cycle` directly. `mu.clock`
  wraps this in the two-thread transport.

  The two-phase `MidiSink` split matters here: `encode` runs on this
  side and may allocate, `emit!` runs on the dispatch thread and may
  not."
  (:require [mu.midi :as midi]
            [mu.pattern :as p]))

(defn default-render-voice
  "Query the pattern for the cycle directly, with no error handling.

  This is the seam's default -- equivalent to what `voice-messages` did
  before the seam existed. `mu.player/begin!` passes something sturdier
  (`safe-render`); this is what plain `mu.clock/start!` gets when nobody
  supplies one, e.g. from a REPL or a test that doesn't need voice-level
  error recovery."
  [_k {:keys [pattern]} cycle-n]
  (p/query pattern [cycle-n (inc cycle-n)]))

(defn- voice-messages
  "Message specs for one voice over one cycle, as
  [{:at-cycle rational :spec m}]. Note-offs may fall past cycle-end.

  `render-voice` is the render seam: a `(fn [k v cycle-n] events)` that
  stands in for querying the pattern directly. Keeping mu.clock generic
  and dependency-free means it cannot reach for `mu.player/safe-render`
  itself, so the caller (mu.player/begin!) injects it instead."
  [render-voice cycle-n k {:keys [chan] :as v}]
  (let [chan (or chan 0)]
    (->> (render-voice k v cycle-n)
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
  allocate, so the boxing happens here instead.

  `render-voice` is an optional per-voice render seam -- a
  `(fn [k v cycle-n] events)` used in place of querying the pattern
  directly, so a caller that wants a bad pattern to be caught and
  replayed (see `mu.player/safe-render`) can inject that behaviour
  without mu.clock knowing mu.player exists. Defaults to a plain,
  unguarded query, which is what every caller got before the seam."
  ([sink voices cycle-n cycle-start-nanos nanos-per-cycle carry-in]
   (render-cycle sink voices cycle-n cycle-start-nanos nanos-per-cycle
                  carry-in default-render-voice))
  ([sink voices cycle-n cycle-start-nanos nanos-per-cycle carry-in render-voice]
   (let [cycle-end (inc cycle-n)
         produced  (mapcat (fn [[k v]] (voice-messages render-voice cycle-n k v)) voices)
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
     {:times times :ats ats :msgs msgs :specs specs :n n :carry (vec later)})))
