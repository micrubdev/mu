(ns mu.notation
  "The s-expression notation surface.

  `notes` is a macro, so bare symbols are never resolved as vars. A
  symbol's SPELLING decides what it is: anything matching the pitch
  grammar is a note, everything else is left alone to resolve normally.
  That single rule is why `(cyc bb4 a4)` means two alternating notes
  while `(rev riff)` still resolves `riff`, inside the same form, with
  no unquote operator.

  A KEYWORD is a drum name -- `(notes :bd _ :sn)` -- carried as
  {:drum :bd} for `mu.kit/kit` to resolve later. Keywords rather than
  bare `bd`/`sn` symbols, because a bare-symbol surface would need a
  closed list of known drum names to tell a drum from a var: a second,
  arbitrary rule beside \"spelling decides\", shadowing any var a user
  names `bd`, with no way in for a custom kit's names.

  A `notes` body is note-literal notation, and this is true of nested
  forms too: `(notes (arp :up p))` reads `:up` as a drum, exactly as
  `(notes (fast 2 c4))` already reads `2` as a note. Transforms wrap a
  `notes` form from outside -- `(arp :up (notes ...))` -- they do not
  live inside one."
  (:require [clojure.string]
            [mu.pattern :as p]))

(def ^:private note-re #"^([a-g])([sf#b]*)(-?\d)$")

;; ---- articulation suffixes --------------------------------------------
;;
;; A literal may carry expression where it is written: `c4!` accents,
;; `c4?` softens, `c4!75` sets velocity, `c4*` asks for modulation, `c4>`
;; holds into the next onset. Borrowed from textbeat, whose `~` for
;; vibrato is Clojure's unquote and so becomes `*` here.

(def ^:private suffix-re #"^(.*?)((?:![0-9]{0,3}|[?*>])*)$")
(def ^:private suffix-token-re #"![0-9]{0,3}|[?*>]")

(defn- percent
  "textbeat's velocity digits: `75` is 0.75, `5` is 0.5, `333` is 0.333,
  `100` is 1.0 -- a digit string read as `0.<digits>`, with the one
  three-digit value that would exceed it pinned to full."
  ^double [digits]
  (if (= digits "100")
    1.0
    (Double/parseDouble (str "0." digits))))

(defn- suffix->map
  "The value keys one suffix string asks for."
  [suffix]
  (reduce (fn [m tok]
            (case (first tok)
              \! (assoc m :vel (let [d (subs tok 1)]
                                 (if (or (= d "") (= d "!")) 1.0 (percent d))))
              \? (assoc m :vel 0.4)
              \* (assoc m :mod 1.0)
              \> (assoc m :legato true)))
          {}
          (re-seq suffix-token-re (clojure.string/replace suffix "!!" "!100"))))

(defn split-suffix
  "Split a literal's name into [stem suffix-map]. A name with no suffix
  gives an empty map."
  [s]
  (let [[_ stem suffix] (re-matches suffix-re s)]
    [stem (suffix->map suffix)]))

(def ^:private pitch-class {\c 0, \d 2, \e 4, \f 5, \g 7, \a 9, \b 11})

(defn- accidentals
  "Net semitone shift of an accidental string. `s`/`#` sharpen, `f`/`b`
  flatten."
  ^long [accs]
  (reduce (fn [acc c]
            (case c
              (\s \#) (inc acc)
              (\f \b) (dec acc)))
          0
          accs))

(defn note-name->midi
  "MIDI number for a note-name symbol/string, or nil if it is not one.
  Middle C is c4 = 60. `s` and `#` sharpen; `f` and `b` flatten."
  [sym]
  (when-let [[_ letter accs octave] (re-matches note-re (name sym))]
    (+ (pitch-class (first letter))
       (accidentals accs)
       (* 12 (inc (Long/parseLong octave))))))

(defn note-name->spell
  "How a note-name symbol/string/keyword is WRITTEN -- letter,
  accidental, octave -- or nil if it is not a note name.

  `(notes ef3)` has always known this and thrown it away. E-flat and
  D-sharp are the same MIDI number and different music."
  [sym]
  (when-let [[_ letter accs octave] (re-matches note-re (name sym))]
    {:step   (keyword letter)
     :alter  (accidentals accs)
     :octave (Long/parseLong octave)}))

(defn- literal
  "The value map a symbol or keyword literal denotes, or nil when its
  spelling is not in the grammar. The suffix is stripped first, so the
  stem is matched by the same rules as before suffixes existed."
  [form]
  (let [[stem art] (split-suffix (name form))]
    (cond
      (keyword? form)
      (merge {:drum (keyword stem)} art)

      :else
      (when-let [m (note-name->midi stem)]
        (merge {:note m :spell (note-name->spell stem)} art)))))

(defn- rewrite
  "Rewrite one form of notation source. Applied recursively."
  [form]
  (cond
    (= form '_)    `p/silence
    (symbol? form) (if-let [v (literal form)]
                     `(p/pure ~v)
                     form)
    (number? form) `(p/pure {:note ~form})
    (keyword? form) `(p/pure ~(literal form))
    (vector? form) `(p/sub ~@(map rewrite form))
    (seq? form)    (cons (first form) (map rewrite (rest form)))
    :else          form))

(defmacro notes
  "Build a pattern from note-literal notation. The body is subdivided
  across one cycle: `(notes c4 _ [eb4 g4])` is three equal steps, the
  last of which splits in two."
  [& body]
  `(p/sub ~@(map rewrite body)))
