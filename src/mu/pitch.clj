(ns mu.pitch
  "Spelled pitch: the letter, accidental and octave a note is WRITTEN as.

  MIDI 51 is both D-sharp 3 and E-flat 3. `:note` says how a note
  sounds; `:spell` says how it is written. Spelling is ADVISORY --
  nothing enforces agreement, because `fmap` with an arbitrary user
  function is the normal way to change a note and an open map cannot
  intercept it. `spelled` is the only reader, and it trusts a carried
  spelling only when it agrees with `:note`, so drift degrades to a
  plain spelling rather than a wrong notehead."
  (:require [clojure.string]
            [mu.pattern :as p]))

(def steps
  "The letter cycle, in order. Index is what `advance` walks."
  [:c :d :e :f :g :a :b])

(def ^:private naturals
  {:c 0 :d 2 :e 4 :f 5 :g 7 :a 9 :b 11})

(defn natural
  "Semitones above C for a letter."
  ^long [step]
  (long (naturals step)))

(defn spell->midi
  "The sounding pitch of a written one. A missing :alter is natural."
  ^long [{:keys [step alter octave]}]
  (+ (natural step) (long (or alter 0)) (* 12 (inc (long octave)))))

(def ^:private default-table
  ;; Flats, not sharps. mu's notation writes eb2, never d#2 -- the NAMES
  ;; table in client/src/hud.js says so outright -- and a sharp default
  ;; would print D-sharp for a pattern the user wrote as eb2.
  [[:c 0] [:d -1] [:d 0] [:e -1] [:e 0] [:f 0]
   [:g -1] [:g 0] [:a -1] [:a 0] [:b -1] [:b 0]])

(defn default-spell
  "The spelling to use when none is carried, or the carried one is stale."
  [note]
  (let [n            (long note)
        [step alter] (nth default-table (mod n 12))]
    {:step step :alter alter :octave (dec (Math/floorDiv n 12))}))

(defn spelled
  "This event's spelling -- always a spell map, never nil.

  The carried :spell is returned only when it agrees with :note. This is
  the single place that rule lives."
  [{:keys [note spell]}]
  (if (and spell (= (long note) (spell->midi spell)))
    spell
    (default-spell note)))

(defn advance
  "Move a spelling `letter-steps` letters on, landing on `target-midi`.

  The letter decides the octave, not the sounding pitch: B-sharp 3 and
  C4 are the same note and different letters, and this is what keeps
  that straight. `Math/floorDiv`, not `quot`, so walking backwards past
  C drops an octave correctly."
  [{:keys [step octave]} letter-steps target-midi]
  (let [i   (+ (.indexOf ^java.util.List steps step) (long letter-steps))
        idx (mod i 7)
        oct (+ (long octave) (Math/floorDiv (long i) 7))
        st  (nth steps idx)]
    {:step   st
     :alter  (- (long target-midi) (+ (natural st) (* 12 (inc oct))))
     :octave oct}))

(def intervals
  "Named intervals as [letter-steps semitones].

  The letter-step count is the whole point: C up a major third is E --
  two letters on -- not F-flat, and only the name carries that."
  {:P1 [0 0]  :m2 [1 1]  :M2 [1 2]  :m3 [2 3]  :M3 [2 4]
   :P4 [3 5]  :A4 [3 6]  :d5 [4 6]  :P5 [4 7]  :m6 [5 8]
   :M6 [5 9]  :m7 [6 10] :M7 [6 11] :P8 [7 12]})

(defn transpose
  "Move a pattern by a named interval, keeping the spelling exact.

    (transpose :M3 (notes c4 ef3))   ; => E4 and G3

  Semitones would not do: up one from C is equally C-sharp or D-flat,
  so only a named interval carries enough to spell the result. An event
  with no :spell gets only :note moved."
  [interval p]
  (let [[ls semis]
        (or (intervals interval)
            (throw (ex-info (str "mu: unknown interval " (pr-str interval) ". Known: "
                                 (clojure.string/join ", " (sort (map name (keys intervals)))))
                            {:interval interval :known (set (keys intervals))})))]
    (p/fmap (fn [v]
              (if (and (map? v) (contains? v :note))
                (let [n (+ (long (:note v)) (long semis))]
                  (cond-> (assoc v :note n)
                    (:spell v) (assoc :spell (advance (:spell v) ls n))))
                v))
            p)))
