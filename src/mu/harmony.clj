(ns mu.harmony
  "Scales and chords: integer degrees in, MIDI notes out.

  A pattern carries integer scale DEGREES; `scale` maps them to MIDI
  against a root. Re-rooting or re-moding a whole line is then one
  edit, which is the entire point of working in degrees.

  `notes` already emits {:note 0} for a bare number, so degree patterns
  need no separate entry point -- `scale` simply remaps :note.

  Purity boundary: as with `mu.pattern`, nothing here reads wall-clock
  time, touches MIDI, or holds mutable state."
  (:require [clojure.string :as str]
            [mu.notation :as n]
            [mu.pattern :as p]))

(def modes
  "Interval sets, in semitones above the root."
  {:ionian         [0 2 4 5 7 9 11]
   :major          [0 2 4 5 7 9 11]
   :dorian         [0 2 3 5 7 9 10]
   :phrygian       [0 1 3 5 7 8 10]
   :lydian         [0 2 4 6 7 9 11]
   :mixolydian     [0 2 4 5 7 9 10]
   :aeolian        [0 2 3 5 7 8 10]
   :minor          [0 2 3 5 7 8 10]
   :locrian        [0 1 3 5 6 8 10]
   :harmonic-minor [0 2 3 5 7 8 11]
   :melodic-minor  [0 2 3 5 7 9 11]
   :major-pent     [0 2 4 7 9]
   :minor-pent     [0 3 5 7 10]
   :blues          [0 3 5 6 7 10]
   :chromatic      [0 1 2 3 4 5 6 7 8 9 10 11]})

(defn- intervals-for
  "Look up a mode, or throw naming the ones that exist. Throwing at
  construction time means the typo surfaces at the REPL on eval, and
  the voice keeps playing its previous var until you fix it."
  [mode]
  (or (modes mode)
      (throw (ex-info (str "mu: unknown mode " (pr-str mode) ". Known: "
                           (str/join ", " (sort (map name (keys modes)))))
                      {:mode mode :known (set (keys modes))}))))

(defn- root->midi
  "A root is a MIDI number, or a note name as a keyword, symbol or
  string -- :d3, 'd3, \"d3\" all work, because `note-name->midi` goes
  through `name`."
  ^long [root]
  (cond
    (number? root)
    (long root)

    (or (keyword? root) (symbol? root) (string? root))
    (or (n/note-name->midi root)
        (throw (ex-info (str "mu: not a note name: " (pr-str root))
                        {:root root})))

    :else
    (throw (ex-info (str "mu: bad root: " (pr-str root)) {:root root}))))

(defn- degree->midi
  "Degrees beyond the scale wrap into higher octaves; negative degrees
  fall below the root. `Math/floorDiv` -- not `quot` -- is what makes
  the negative case right, and matches the idiom in `mu.clock`."
  ^long [intervals ^long root ^long degree]
  (let [size   (count intervals)
        octave (Math/floorDiv degree (long size))
        idx    (mod degree size)]
    (+ root (long (nth intervals idx)) (* 12 octave))))

(defn scale
  "Map integer scale degrees to MIDI notes.

  `mode` is a keyword from `modes`; `root` is a note-name keyword (:d3)
  or a raw MIDI number. Each event's :note is read as a degree, rounded
  to nearest. Events whose value is not a map, or carries no :note, pass
  through untouched -- so `scale` is safe downstream of `with`.

    (scale :dorian :d3 (notes 0 2 4 [6 4]))

  Timing is never touched: this is a value map."
  [mode root p]
  (let [intervals (intervals-for mode)
        r         (root->midi root)]
    (p/fmap (fn [v]
              (if (and (map? v) (contains? v :note))
                (assoc v :note (degree->midi intervals r
                                             (Math/round (double (:note v)))))
                v))
            p)))
