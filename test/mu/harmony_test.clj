(ns mu.harmony-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.harmony :as h]
            [mu.notation :as n]
            [mu.pattern :as p]
            [mu.pitch :as pitch]))

(defn- degrees->notes
  "Run a sequence of integer degrees through a scale, in cycle order."
  [mode root ds]
  (->> (apply p/sub (map #(p/pure {:note %}) ds))
       (h/scale mode root)
       (#(p/query % [0 1]))
       (map (comp :note :value))))

(deftest scale-maps-degrees-to-midi
  (testing "degree 0 is the root"
    (is (= [60] (degrees->notes :major :c4 [0]))))
  (testing "degree 7 is the octave above"
    (is (= [72] (degrees->notes :major :c4 [7]))))
  (testing "degree -1 falls below the root: the leading tone"
    (is (= [59] (degrees->notes :major :c4 [-1]))))
  (testing "degree -7 is a full octave below"
    (is (= [48] (degrees->notes :major :c4 [-7]))))
  (testing "a whole major octave"
    (is (= [60 62 64 65 67 69 71 72]
           (degrees->notes :major :c4 (range 8)))))
  (testing "dorian on d3"
    (is (= [50 52 53 55 57 59 60 62]
           (degrees->notes :dorian :d3 (range 8))))))

(deftest scale-accepts-either-root-form
  (testing "a raw MIDI root agrees with the note-name keyword"
    (is (= (degrees->notes :dorian :d3 (range 8))
           (degrees->notes :dorian 50 (range 8))))))

(deftest scale-handles-non-seven-note-modes
  (testing "minor pentatonic wraps after five degrees"
    (is (= [60 63 65 67 70 72] (degrees->notes :minor-pent :c4 (range 6)))))
  (testing "chromatic wraps after twelve"
    (is (= [60 61 72] (degrees->notes :chromatic :c4 [0 1 12])))))

(deftest scale-aliases-agree
  (is (= (degrees->notes :major :c4 (range 8))
         (degrees->notes :ionian :c4 (range 8))))
  (is (= (degrees->notes :minor :c4 (range 8))
         (degrees->notes :aeolian :c4 (range 8)))))

(deftest scale-rounds-non-integer-degrees
  (testing "halves round up, so 3/2 becomes degree 2"
    (is (= (degrees->notes :major :c4 [2]) (degrees->notes :major :c4 [3/2])))))

(deftest scale-rejects-unknown-modes
  (testing "a typo throws, naming the known modes"
    (is (thrown? clojure.lang.ExceptionInfo
                 (h/scale :dorain :c4 (p/pure {:note 0}))))))

(deftest scale-rejects-bad-roots
  (is (thrown? clojure.lang.ExceptionInfo
               (h/scale :major :h9 (p/pure {:note 0})))))

(deftest scale-leaves-non-note-values-alone
  (testing "a bare scalar survives, so scale is safe downstream of with"
    (is (= [0.5] (map :value (p/query (h/scale :major :c4 (p/pure 0.5)) [0 1])))))
  (testing "a map with no :note key survives"
    (is (= [{:vel 0.5}]
           (map :value (p/query (h/scale :major :c4 (p/pure {:vel 0.5})) [0 1])))))
  (testing "sibling keys are preserved alongside the mapped note"
    (let [v (first (map :value (p/query (h/scale :major :c4 (p/pure {:note 2 :vel 0.5}))
                                        [0 1])))]
      (is (= 64 (:note v)))
      (is (= 0.5 (:vel v)) "the sibling key survives")
      (is (= {:step :e :alter 0 :octave 4} (:spell v)) "and the degree is spelled"))))

(deftest scale-preserves-timing
  (let [src (p/sub (p/pure {:note 0}) (p/pure {:note 2}))
        before (map (juxt :whole :part) (p/query src [0 1]))
        after  (map (juxt :whole :part) (p/query (h/scale :major :c4 src) [0 1]))]
    (is (= before after) "scale is a value map; it must not move anything")))

(defn- chord-notes
  "Degrees -> chords -> MIDI, in cycle order. Note the nesting: chord
  runs INSIDE scale, on degrees."
  [mode root size ds]
  (->> (apply p/sub (map #(p/pure {:note %}) ds))
       (h/chord size)
       (h/scale mode root)
       (#(p/query % [0 1]))
       (map (comp :note :value))))

(deftest chord-stacks-diatonic-degrees
  (testing "a triad on degree 0 of C major is C E G"
    (is (= [60 64 67] (chord-notes :major :c4 3 [0]))))
  (testing "size 4 adds the seventh"
    (is (= [60 64 67 71] (chord-notes :major :c4 4 [0]))))
  (testing "size defaults to a triad"
    (is (= (chord-notes :major :c4 3 [0])
           (->> (p/pure {:note 0}) h/chord (h/scale :major :c4)
                (#(p/query % [0 1])) (map (comp :note :value)))))))

(deftest chord-quality-falls-out-of-the-mode
  (testing "dorian degree 4 is minor: 3 and 7 semitones above its root"
    (let [[a b c] (chord-notes :dorian :d3 3 [4])]
      (is (= [3 7] [(- b a) (- c a)]))))
  (testing "locrian degree 0 is diminished: 3 and 6"
    (let [[a b c] (chord-notes :locrian :c4 3 [0])]
      (is (= [3 6] [(- b a) (- c a)]))))
  (testing "major degree 4 is major: 4 and 7"
    (let [[a b c] (chord-notes :major :c4 3 [4])]
      (is (= [4 7] [(- b a) (- c a)])))))

(deftest chord-preserves-timing-exactly
  (let [src (p/pure {:note 0})
        ev  (first (p/query src [0 1]))
        evs (p/query (h/chord 3 src) [0 1])]
    (is (= 3 (count evs)))
    (is (every? #(= (:whole ev) (:whole %)) evs) "same whole")
    (is (every? #(= (:part ev) (:part %)) evs) "same part")
    (is (every? p/onset? evs) "a chord is one onset, not several")))

(deftest chord-leaves-non-note-values-alone
  (is (= [0.5] (map :value (p/query (h/chord 3 (p/pure 0.5)) [0 1]))))
  (is (= [{:vel 0.5}]
         (map :value (p/query (h/chord 3 (p/pure {:vel 0.5})) [0 1])))))

(deftest chord-preserves-sibling-keys
  (is (= [{:note 0 :vel 0.5} {:note 2 :vel 0.5} {:note 4 :vel 0.5}]
         (map :value (p/query (h/chord 3 (p/pure {:note 0 :vel 0.5})) [0 1])))))

(defn- spells [mode root ds]
  (->> (apply p/sub (map #(p/pure {:note %}) ds))
       (h/scale mode root)
       (#(p/query % [0 1]))
       (map (comp :spell :value))))

(deftest scale-spells-heptatonic-degrees-by-letter
  (testing "D dorian: degree 2 is an F, not an E-sharp"
    (is (= [{:step :f :alter 0 :octave 3}] (spells :dorian :d3 [2]))))
  (testing "degree 7 is the octave, one letter cycle up"
    (is (= [{:step :d :alter 0 :octave 4}] (spells :dorian :d3 [7]))))
  (testing "negative degrees wrap the letter and the octave down"
    (is (= [{:step :b :alter 0 :octave 2}] (spells :dorian :d3 [-2]))))
  (testing "a whole octave of C major is C D E F G A B C"
    (is (= [:c :d :e :f :g :a :b :c] (map :step (spells :major :c4 (range 8))))))
  (testing "E-flat major spells flats, not sharps"
    (is (= [{:step :e :alter -1 :octave 3} {:step :f :alter 0 :octave 3}
            {:step :g :alter 0 :octave 3} {:step :a :alter -1 :octave 3}]
           (spells :major :ef3 [0 1 2 3])))))

(deftest scale-spellings-always-agree-with-the-note
  (doseq [mode [:major :minor :dorian :lydian :locrian :harmonic-minor :major-pent :minor-pent]
          root [:c4 :ef3 :d3]]
    (let [q (->> (apply p/sub (map #(p/pure {:note %}) (range -3 9)))
                 (h/scale mode root))]
      (doseq [{:keys [note spell]} (map :value (p/query q [0 1]))]
        (when spell
          (is (= note (pitch/spell->midi spell))
              (str mode " " root " " note " " (pr-str spell))))))))

(deftest pentatonics-inherit-their-parent-spelling
  (testing "C minor pentatonic is C Eb F G Bb"
    (is (= [:c :e :f :g :b] (map :step (spells :minor-pent :c4 (range 5)))))
    (is (= [0 -1 0 0 -1] (map :alter (spells :minor-pent :c4 (range 5))))))
  (testing "C major pentatonic is C D E G A"
    (is (= [:c :d :e :g :a] (map :step (spells :major-pent :c4 (range 5)))))))

(deftest some-modes-honestly-cannot-spell
  (testing "chromatic has no canonical letter per degree"
    (is (= [nil nil nil] (spells :chromatic :c4 [0 1 2]))))
  (testing "blues likewise"
    (is (every? nil? (spells :blues :c4 (range 6)))))
  (testing "nor can a raw MIDI root, which has no letter"
    (is (= [nil] (spells :major 60 [0])))))

(deftest scale-drops-a-spelling-it-cannot-vouch-for
  (testing "a spelling carried in from `notes` describes the DEGREE, not the pitch"
    (let [q (h/scale :chromatic :c4 (n/notes c4))]
      (is (nil? (:spell (:value (first (p/query q [0 1])))))
          "the incoming c4 spelling must not survive as stale"))))
