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
            [mu.pattern :as p]
            [mu.pitch :as pitch]))

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

(def ^:private heptatonic
  "Modes whose degrees map one-to-one onto letters. They are all
  rotations of the major scale, so degree i is simply the root's letter
  advanced i steps -- no per-mode letter table is needed."
  #{:ionian :major :dorian :phrygian :lydian :mixolydian
    :aeolian :minor :locrian :harmonic-minor :melodic-minor})

(def ^:private pentatonic-parents
  "Which degrees of the parent heptatonic scale a pentatonic picks out.
  Spelling a pentatonic degree means spelling the parent degree."
  {:major-pent [0 1 2 4 5]     ; of the major scale
   :minor-pent [0 2 3 4 6]})   ; of the natural minor

(defn- root->spell
  "A note-name root can be spelled; a raw MIDI root has no letter."
  [root]
  (when-not (number? root) (n/note-name->spell root)))

(defn- degree-spell
  "How this degree is written, or nil when the mode has no canonical
  letter for it -- `:blues` and `:chromatic`, or any mode under a raw
  MIDI root. Guessing would print a wrong glyph, which is worse than
  printing a plain one."
  [mode root-spell degree midi]
  (when root-spell
    (cond
      (heptatonic mode)
      (pitch/advance root-spell degree midi)

      (pentatonic-parents mode)
      (let [par (pentatonic-parents mode)
            n   (count par)]
        (pitch/advance root-spell
                       (+ (long (nth par (mod degree n)))
                          (* 7 (Math/floorDiv (long degree) n)))
                       midi))

      :else nil)))

(defn scale
  "Map integer scale degrees to MIDI notes.

  `mode` is a keyword from `modes`; `root` is a note-name keyword (:d3)
  or a raw MIDI number. Each event's :note is read as a degree, rounded
  to nearest. Events whose value is not a map, or carries no :note, pass
  through untouched -- so `scale` is safe downstream of `with`.

    (scale :dorian :d3 (notes 0 2 4 [6 4]))

  Adds :spell where the mode has a canonical letter per degree -- the
  seven-note modes and the pentatonics. `:blues`, `:chromatic` and a raw
  MIDI root spell nothing, and any incoming :spell is removed rather
  than left stale: a spelling carried in from `notes` describes the
  degree, not the pitch it becomes.

  Timing is never touched: this is a value map."
  [mode root p]
  (let [intervals (intervals-for mode)
        r         (root->midi root)
        rs        (root->spell root)]
    (p/fmap (fn [v]
              (if (and (map? v) (contains? v :note))
                (let [d (Math/round (double (:note v)))
                      m (degree->midi intervals r d)
                      s (degree-spell mode rs d m)]
                  (cond-> (assoc v :note m)
                    s        (assoc :spell s)
                    (nil? s) (dissoc :spell)))
                v))
            p)))

(defn chord
  "Turn each scale degree into a `size`-note diatonic stack: degrees
  d, d+2, d+4, ... Defaults to a triad.

  Runs INSIDE `scale`, on degrees, before they become MIDI notes:

    (scale :dorian :d3
      (chord 3 (notes 0 3 4 3)))   ; correct quality per degree, free

  Reversed, it would stack semitone offsets on absolute MIDI numbers
  and produce nonsense.

  Timing is untouched -- every note of the stack carries the source
  event's :whole and :part, so a chord is one onset, not several.
  Unlike `fmap` this maps one event to many, hence the mapcat."
  ([p] (chord 3 p))
  ([size p]
   (p/pat (fn [sp]
            (mapcat (fn [{:keys [value] :as ev}]
                      (if (and (map? value) (contains? value :note))
                        (for [i (range size)]
                          (assoc ev :value (update value :note + (* 2 i))))
                        [ev]))
                    (p/query p sp))))))
