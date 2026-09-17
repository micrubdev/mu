(ns mu.notation-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.pattern :as p]
            [mu.pitch :as pitch]
            [mu.notation :refer [notes deg note-name->midi note-name->spell]]))

(defn- onset-values [pt cyc]
  (->> (p/query pt [cyc (inc cyc)])
       (filter p/onset?)
       (sort-by (comp first :part))
       (map :value)))

(deftest note-names-parse-to-midi
  (testing "middle C is 60"
    (is (= 60 (note-name->midi 'c4))))
  (testing "accidentals, both spellings"
    (is (= 61 (note-name->midi 'cs4)))
    (is (= 61 (note-name->midi 'c#4)))
    (is (= 63 (note-name->midi 'ef4)))
    (is (= 63 (note-name->midi 'eb4))))
  (testing "b is both a letter and a flat"
    (is (= 47 (note-name->midi 'b2)))
    (is (= 46 (note-name->midi 'bb2)) "B-flat 2"))
  (testing "octaves"
    (is (= 0  (note-name->midi 'c-1)))
    (is (= 72 (note-name->midi 'c5))))
  (testing "non-notes are not notes"
    (is (nil? (note-name->midi 'riff)))
    (is (nil? (note-name->midi 'fast)))
    (is (nil? (note-name->midi 'h4)))))

(deftest notes-builds-a-subdivided-pattern
  (is (= [60 62] (map :note (onset-values (notes c4 d4) 0))))
  (is (= [[0 1/2] [1/2 1]]
         (map :whole (sort-by (comp first :part)
                              (p/query (notes c4 d4) [0 1]))))))

(deftest underscore-is-a-rest
  (is (= [60] (map :note (onset-values (notes c4 _) 0))))
  (is (= 1 (count (p/query (notes c4 _) [0 1])))))

(deftest vectors-subdivide
  (let [pt (notes c4 [d4 e4])]
    (is (= [60 62 64] (map :note (onset-values pt 0))))
    (is (= [[0 1/2] [1/2 3/4] [3/4 1]]
           (map :whole (sort-by (comp first :part) (p/query pt [0 1])))))))

(deftest raw-numbers-are-midi-notes
  (testing "exact map: a raw number carries NO spelling to add"
    (is (= [{:note 36}] (onset-values (notes 36) 0)))))

(deftest lists-are-calls-whose-arguments-are-rewritten
  (testing "note literals work inside a call"
    (is (= [70] (map :note (onset-values (notes (p/cyc bb4 a4)) 0))))
    (is (= [69] (map :note (onset-values (notes (p/cyc bb4 a4)) 1)))))
  (testing "a non-note symbol inside a call still resolves as a var"
    (let [riff (notes c4 d4)]
      (is (= [62 60] (map :note (onset-values (notes (p/rev riff)) 0)))))))

(deftest note-name-to-spell
  (is (= {:step :e :alter -1 :octave 3} (note-name->spell 'ef3)))
  (is (= {:step :e :alter -1 :octave 3} (note-name->spell 'eb3)) "b flattens too")
  (is (= {:step :c :alter 1 :octave 4} (note-name->spell 'cs4)))
  (is (= {:step :c :alter 1 :octave 4} (note-name->spell "c#4")))
  (is (= {:step :c :alter 0 :octave -1} (note-name->spell 'c-1)))
  (is (= {:step :b :alter 1 :octave 3} (note-name->spell 'bs3)))
  (is (nil? (note-name->spell 'riff)) "not a note name"))

(deftest literals-carry-the-spelling-they-were-written-with
  (let [vals (fn [q] (map :value (p/query q [0 1])))]
    (testing "e-flat is spelled e-flat, never d-sharp"
      (is (= [{:note 51 :spell {:step :e :alter -1 :octave 3}}]
             (vals (notes ef3)))))
    (testing "a raw MIDI number carries no spelling"
      (is (= [{:note 51}] (vals (notes 51)))))
    (testing "the spelling always agrees with the note"
      (doseq [q [(notes c4) (notes ef3) (notes cs4) (notes c-1) (notes bs3)]
              v (vals q)]
        (is (= (:note v) (pitch/spell->midi (:spell v)))
            (str "disagreement in " (pr-str v)))))))

;; ---- drum names -------------------------------------------------------

(deftest a-keyword-is-a-drum-name
  (is (= [{:drum :bd}]
         (map :value (p/query (notes :bd) [0 1])))))

(deftest drum-names-subdivide-like-notes
  (let [evs (->> (p/query (notes :bd _ :sn [:hh :hh]) [0 1])
                 (filter p/onset?)
                 (sort-by (comp first :whole)))]
    (is (= [{:drum :bd} {:drum :sn} {:drum :hh} {:drum :hh}]
           (map :value evs)))
    (is (= [[0 1/4] [1/2 3/4] [3/4 7/8] [7/8 1]]
           (map :whole evs)))))

(deftest drums-and-notes-mix-in-one-body
  (is (= [{:drum :bd} {:note 60 :spell {:step :c :alter 0 :octave 4}}]
         (map :value (->> (p/query (notes :bd c4) [0 1])
                          (sort-by (comp first :whole)))))))

(deftest a-bare-symbol-still-resolves
  (let [riff (notes c4)]
    (is (= [{:note 60 :spell {:step :c :alter 0 :octave 4}}]
           (map :value (p/query (notes riff) [0 1]))))))

;; ---- articulation suffixes --------------------------------------------

(defn- vals-of [q] (map :value (p/query q [0 1])))

(deftest accent-and-soften-set-velocity
  (is (= 1.0 (:vel (first (vals-of (notes c4!))))))
  (is (= 0.4 (:vel (first (vals-of (notes c4?))))))
  (testing "the note itself is unchanged"
    (is (= 60 (:note (first (vals-of (notes c4!))))))
    (is (= {:step :c :alter 0 :octave 4} (:spell (first (vals-of (notes c4!))))))))

(deftest explicit-velocity-is-a-percentage
  (is (= 0.75  (:vel (first (vals-of (notes c4!75))))))
  (is (= 0.75  (:vel (first (vals-of (notes bb2!75))))) "b is a letter before it is a flat")
  (is (= 0.5   (:vel (first (vals-of (notes c4!5))))))
  (is (= 0.333 (:vel (first (vals-of (notes c4!333))))))
  (is (= 1.0   (:vel (first (vals-of (notes c4!!))))))
  (is (= 1.0   (:vel (first (vals-of (notes c4!100)))))))

(deftest mod-and-legato-suffixes
  (is (= 1.0 (:mod (first (vals-of (notes c4*))))))
  (is (true? (:legato (first (vals-of (notes c4>)))))))

(deftest suffixes-combine-in-any-order
  (is (= {:note 60 :spell {:step :c :alter 0 :octave 4} :vel 1.0 :mod 1.0 :legato true}
         (first (vals-of (notes c4!*>)))))
  (is (= {:note 60 :spell {:step :c :alter 0 :octave 4} :vel 1.0 :mod 1.0 :legato true}
         (first (vals-of (notes c4>*!))))))

(deftest suffixes-on-drums
  (is (= {:drum :bd :vel 0.4} (first (vals-of (notes :bd?)))))
  (is (= {:drum :sn :vel 1.0} (first (vals-of (notes :sn!))))))

(deftest a-suffixed-non-note-symbol-is-not-a-note
  (is (nil? (note-name->midi 'riff!)))
  (is (nil? (note-name->midi 'h4!))))

;; ---- degree notation ----------------------------------------------------

(deftest deg-numbers-are-one-based
  (is (= [{:note 0 :deg true} {:note 2 :deg true} {:note 4 :deg true}]
         (map :value (sort-by (comp first :whole) (p/query (deg 1 3 5) [0 1]))))))

(deftest deg-continues-past-seven-and-below-one
  (is (= [7] (map (comp :note :value) (p/query (deg 8) [0 1]))) "8 is the root an octave up")
  (is (= [-1] (map (comp :note :value) (p/query (deg -1) [0 1]))) "-1 is one degree below the root")
  (is (= [-7] (map (comp :note :value) (p/query (deg -7) [0 1])))))

(deftest deg-flats-and-sharps
  (is (= [{:note 2 :deg true :alter -1}] (map :value (p/query (deg b3) [0 1]))))
  (is (= [{:note 3 :deg true :alter 1}]  (map :value (p/query (deg s4) [0 1]))))
  (is (= [{:note 6 :deg true :alter -2}] (map :value (p/query (deg bb7) [0 1])))))

(deftest deg-takes-articulation-and-rests
  (is (= [{:note 0 :deg true :vel 1.0} {:note 2 :deg true :alter -1 :legato true}]
         (map :value (sort-by (comp first :whole) (p/query (deg n1! _ b3>) [0 1])))))
  (is (= 2 (count (p/query (deg n1! _ b3>) [0 1])))))

(deftest deg-nests-like-notes
  (is (= [[0 1/2] [1/2 3/4] [3/4 1]]
         (map :whole (sort-by (comp first :whole) (p/query (deg 1 [3 5]) [0 1]))))))
