(ns mu.demo-test
  "The demo is a piece, not a library, so these tests ask only what a
  demo can get wrong on its own: a drum name that does not resolve, a
  note off the keyboard, a section that throws when it runs. Whether it
  sounds good is not a thing `is` can answer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mu.demo :as demo]
            [mu.harmony :as h]
            [mu.kit :as k]
            [mu.pattern :as p]
            [mu.player :as pl]))

(def ^:private piece-vars
  [#'demo/boots #'demo/tankards #'demo/organ #'demo/chant #'demo/beeps])

(defn- restore-the-piece
  "Section 4 redefines `boots` -- that is the point of it -- so put the
  root values back and clear the voice registry between tests."
  [f]
  (let [roots (mapv deref piece-vars)]
    (pl/reset-all!)
    (try (f)
         (finally
           (doseq [[v root] (map vector piece-vars roots)]
             (alter-var-root v (constantly root)))
           (pl/reset-all!)))))

(use-fixtures :each restore-the-piece)

(defn- onsets
  "Event values of the onsets in cycle `cyc`, in time order."
  [pat cyc]
  (->> (p/query pat [cyc (inc cyc)])
       (filter p/onset?)
       (sort-by (comp first :part))
       (map :value)))

(defn- over-eight-cycles [pat]
  (mapcat #(onsets pat %) (range 8)))

;; ---- every voice actually plays ----------------------------------------

(deftest every-voice-sounds-in-every-cycle
  (testing "no voice is silent for a whole cycle of the piece"
    (doseq [[nm pat] [[:boots demo/boots] [:tankards demo/tankards]
                      [:organ demo/organ] [:chant demo/chant]
                      [:beeps demo/beeps]]]
      (doseq [cyc (range 8)]
        (is (seq (onsets pat cyc))
            (str nm " has nothing in cycle " cyc))))))

;; ---- the drum parts ----------------------------------------------------

(deftest drum-names-resolve-through-gm
  (testing "an unresolvable name throws; this is where that surfaces"
    (doseq [[nm pat] [[:boots demo/boots] [:tankards demo/tankards]]]
      (let [vs (over-eight-cycles (k/kit k/gm pat))]
        (is (seq vs) (str nm " resolved to nothing"))
        (is (every? :note vs) (str nm " left a drum name unresolved"))
        (is (every? #(<= 35 (:note %) 81) vs)
            (str nm " left the GM percussion range"))
        (is (every? #(= 9 (:chan %)) vs)
            (str nm " is not on the percussion channel"))))))

(deftest the-full-stomp-is-a-drum-part-too
  (testing "the pattern section 4 swaps in resolves like the opening one"
    (let [vs (over-eight-cycles (k/kit k/gm @#'demo/full-stomp))]
      (is (seq vs))
      (is (every? #(<= 35 (:note %) 81) vs)))))

;; ---- the pitched parts -------------------------------------------------

(deftest pitched-voices-land-on-the-keyboard
  (doseq [[nm pat lo hi] [[:organ demo/organ 21 108]
                          [:chant demo/chant 21 108]
                          [:beeps demo/beeps 60 108]]]
    (let [vs (over-eight-cycles pat)]
      (is (every? :note vs) (str nm " produced an event with no :note"))
      (is (every? #(<= lo (:note %) hi) vs)
          (str nm " went off the keyboard: "
               (remove #(<= lo (:note %) hi) vs))))))

(deftest pitched-voices-carry-no-drum-names
  (testing "so `play!`'s unconditional kit is a no-op on them"
    (doseq [pat [demo/organ demo/chant demo/beeps]]
      (is (every? (complement :drum) (over-eight-cycles pat))))))

;; ---- the octave stop ---------------------------------------------------

(deftest the-octave-doubling-is-an-actual-octave
  (testing "degree 7 is the octave, so `octave-up` runs on DEGREES and
            adds 7 -- adding 12 there would be twelve scale steps, an
            octave and a half, and still land on the keyboard"
    (let [octave-up @#'demo/octave-up
          notes-of  (fn [pat] (map :note (over-eight-cycles
                                          (h/scale :aeolian :d3 pat))))
          plain     (notes-of @#'demo/progression)
          doubled   (notes-of (octave-up @#'demo/progression))]
      (is (seq plain))
      (is (= (map #(+ 12 %) plain) doubled)
          "the doubling is not one octave above the line it doubles"))))

;; ---- the arrangement ---------------------------------------------------

(deftest sections-are-well-formed
  (is (seq demo/sections))
  (doseq [{:keys [name bars enter bpm]} demo/sections]
    (is (string? name))
    (is (and (integer? bars) (pos? bars)) (str name " has no bar count"))
    (is (ifn? enter) (str name " has no :enter"))
    (when bpm
      (is (and (number? bpm) (< 20 bpm 300)) (str name " has a wild bpm")))))

(deftest walking-the-arrangement-registers-every-voice
  (testing "each :enter runs against the real registry, with no transport"
    (doseq [{:keys [enter]} demo/sections]
      (enter))
    (is (= #{:boots :tankards :organ :chant :beeps}
           (set (keys (pl/voices)))))))

(deftest the-arrangement-ends-with-something-still-sounding
  (testing "the last section fades voices out; it must not silence them all"
    (doseq [{:keys [enter]} demo/sections]
      (enter))
    (is (seq (pl/current-voices))
        "every voice was muted by the end -- the outro is silence")))
