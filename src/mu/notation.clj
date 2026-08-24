(ns mu.notation
  "The s-expression notation surface.

  `notes` is a macro, so bare symbols are never resolved as vars. A
  symbol's SPELLING decides what it is: anything matching the pitch
  grammar is a note, everything else is left alone to resolve normally.
  That single rule is why `(cyc bb4 a4)` means two alternating notes
  while `(rev riff)` still resolves `riff`, inside the same form, with
  no unquote operator."
  (:require [mu.pattern :as p]))

(def ^:private note-re #"^([a-g])([sf#b]*)(-?\d)$")

(def ^:private pitch-class {\c 0, \d 2, \e 4, \f 5, \g 7, \a 9, \b 11})

(defn note-name->midi
  "MIDI number for a note-name symbol/string, or nil if it is not one.
  Middle C is c4 = 60. `s` and `#` sharpen; `f` and `b` flatten."
  [sym]
  (when-let [[_ letter accs octave] (re-matches note-re (name sym))]
    (let [semis (reduce (fn [acc c]
                          (case c
                            (\s \#) (inc acc)
                            (\f \b) (dec acc)))
                        0
                        accs)]
      (+ (pitch-class (first letter))
         semis
         (* 12 (inc (Long/parseLong octave)))))))

(defn- rewrite
  "Rewrite one form of notation source. Applied recursively."
  [form]
  (cond
    (= form '_)    `p/silence
    (symbol? form) (if-let [m (note-name->midi form)]
                     `(p/pure {:note ~m})
                     form)
    (number? form) `(p/pure {:note ~form})
    (vector? form) `(p/sub ~@(map rewrite form))
    (seq? form)    (cons (first form) (map rewrite (rest form)))
    :else          form))

(defmacro notes
  "Build a pattern from note-literal notation. The body is subdivided
  across one cycle: `(notes c4 _ [eb4 g4])` is three equal steps, the
  last of which splits in two."
  [& body]
  `(p/sub ~@(map rewrite body)))
