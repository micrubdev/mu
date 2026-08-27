(ns mu.preview
  "What an edit will do to the next cycle, before it sounds.

  Both timelines are evaluated at the SAME cycle number -- one under the
  current pattern value, one under the value it replaced -- so per-cycle
  variation cancels. `(every 2 rev p)` shows no difference unless the
  code actually changed.

  The functions here are pure and thread-free. Time stays rational
  throughout: conversion to double happens once, at the wire boundary in
  `mu.web.protocol`, because two distinct rationals can round to the same
  double and silently compare equal in the diff."
  (:require [mu.pattern :as p]))

(def ^:private MAX-NOTES
  "Past this many notes a voice is truncated. `lsys` at generation 16 is
  2584 events; flooding the socket to draw an unreadable smear helps
  nobody."
  512)

(defn notes-in
  "Onset notes of one voice in one cycle, as display tuples with offsets
  relative to the cycle start.

  May throw -- the pattern is arbitrary user code. Callers catch."
  [{:keys [pattern chan]} cycle-n]
  (->> (p/query pattern [cycle-n (inc cycle-n)])
       (filter p/onset?)
       (keep (fn [{:keys [whole value]}]
               (when (and (map? value) (contains? value :note))
                 {:b    (- (first whole) cycle-n)
                  :e    (- (second whole) cycle-n)
                  :note (:note value)
                  :chan (:chan value chan)})))
       (sort-by (juxt :b :note))
       vec))

(defn diff
  "Classify two timelines for the same cycle.

  Identity is the whole tuple, so a note whose duration changed appears
  as an added/removed pair in place. A `modified` class would need a
  matching heuristic, and a heuristic is wrong exactly when the edit is
  subtle -- which is when this is supposed to earn its keep."
  [was now]
  (let [was-set (set was)
        now-set (set now)]
    (->> (concat (map #(assoc % :s (if (was-set %) "same" "added")) now)
                 (map #(assoc % :s "removed") (remove now-set was)))
         (sort-by (juxt :b :note :s))
         vec)))

(defn voice-preview
  "One voice's classified timeline, capped."
  [was now]
  (let [notes (diff was now)]
    (cond-> {:changed (not= was now)
             :notes   (vec (take MAX-NOTES notes))}
      (> (count notes) MAX-NOTES) (assoc :truncated true))))

(defonce ^:private !baseline
  ;; key -> the pattern value seen at the previous tick. The one piece of
  ;; state here: it lives in this namespace because the baseline is part
  ;; of what "changed" MEANS, not part of transporting it.
  (atom {}))

(defn reset-baseline! [] (reset! !baseline {}) nil)

(defn- safe-notes
  "notes-in, but a throwing pattern yields the message instead."
  [voice cycle-n]
  (try {:notes (notes-in voice cycle-n)}
       (catch Throwable t {:error (or (.getMessage t) (str (class t)))})))

(defn tick!
  "One preview per rendered cycle, for the cycle AFTER `cycle-n`.

  Diffs this tick's pattern values against the previous tick's. The
  render thread is one cycle ahead, so cycle-n + 1 is the first cycle an
  edit made now can still reach. Never throws."
  [voices cycle-n]
  (let [target (inc cycle-n)
        base   @!baseline
        out    (into {}
                 (for [[k v] voices]
                   [k (let [{:keys [notes error]} (safe-notes v target)]
                        (if error
                          {:error error :changed false :notes []}
                          (let [prev (get base k)]
                            (cond
                              ;; fast path: the very same pattern object
                              (identical? prev (:pattern v))
                              (voice-preview notes notes)

                              ;; new voice: everything is an addition
                              (nil? prev)
                              (voice-preview [] notes)

                              :else
                              (voice-preview (or (:notes (safe-notes (assoc v :pattern prev) target))
                                                 [])
                                             notes)))))]))]
    (reset! !baseline (into {} (map (fn [[k v]] [k (:pattern v)])) voices))
    {:n target :voices out}))
