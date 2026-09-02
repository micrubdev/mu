(ns mu.demo
  "A one-shot demo piece: `clojure -M:demo`.

  An original tune in the idiom of an Adeptus Mechanicus drinking song
  -- stomping boots, struck tankards, an electric organ, a roaring
  chorus and binaric chatter. Every voice is here to carry a different
  part of mu's vocabulary, so the piece doubles as a tour:

    boots     kit / gm drum names, `cyc` for a four-bar phrase
    tankards  `euclid`, `degrade-by`, a per-event `:vel` pattern
    organ     `scale` + `chord`, `arp` on alternate bars, `superimpose`
    chant     a modal line doubled at the octave, in degrees
    beeps     `lsys` -- a grammar, not a sequence

  D aeolian, 132 bpm. A cycle is a bar; the parts divide it in six for
  the compound lurch a drinking song wants.

  TIMBRE. `mu.midi` speaks note-on, note-off and CC -- there is no
  program change -- so on Gervill every melodic channel is a grand
  piano and the organ will not sound like an organ. The structure is
  what this demonstrates. Point it at a synth that is already set up
  (`clojure -M:demo \"IAC\"`) to hear it as written."
  (:refer-clojure :exclude [rand])
  (:require [mu.live :refer :all]))

;; ---- the parts ---------------------------------------------------------

(def ^:private stomp
  "Two dotted-quarter stomps to the bar. The whole piece leans on this."
  (notes :bd _ _ :bd _ _))

(def ^:private fill
  "Boots coming down the ladder."
  (notes :ht :mt :ft :lt))

(def boots
  "A four-bar phrase: three bars of stomping, then half a bar of it with
  a tom spill on the back half."
  (cyc stomp stomp stomp (sub stomp fill)))

(def ^:private full-stomp
  "What the boots become once the chorus starts -- the gap on beat two
  fills in and the phrase ends on a floor tom."
  (cyc (notes :bd _ :bd :bd _ :ft)
       (notes :bd _ :bd :bd _ :ft)
       (notes :bd _ :bd :bd _ :ft)
       (sub (notes :bd _ :bd :bd _ :ft) fill)))

(def tankards
  "Tankards struck on the table: five in six, accented by a velocity
  pattern that runs at its own rate, and thinned so it stays a room
  full of people rather than a machine."
  (degrade-by 0.25
              (stack (with (euclid 5 6 (notes :tb))
                           :vel (sub 0.9 0.55 0.7 0.5 0.85 0.6))
                     (late 1/6 (euclid 2 6 (notes :cb))))))

(defn- octave-up
  "Up one octave, in DEGREES -- degree 7 is the octave, so this runs
  inside `scale`, before degrees become note numbers."
  [p]
  (fmap #(update % :note + 7) p))

(def ^:private progression
  "i - bVII - bVI - v, one chord to the bar. The qualities fall out of
  the mode; nothing here names a minor chord."
  (chord 3 (slow 4 (notes 0 6 5 4))))

(def organ
  "Block chords, broken into a rolling arpeggio on alternate bars, with
  an octave doubling on top the way an organ stop doubles."
  (scale :aeolian :d3
         (superimpose octave-up (every 2 #(arp :up %) progression))))

(def chant
  "The roaring chorus: one low modal line, doubled at the octave, two
  bars long."
  (scale :aeolian :d2
         (superimpose octave-up
                      (slow 2 (notes 0 2 3 4 _ 4 3 2)))))

(def ^:private binaric
  "The machine's own grammar. Three symbols, so the chatter has three
  pitches; it grows about 1.7x a generation, nowhere near the cap."
  {0 [0 2], 2 [0 1], 1 [2]})

(def beeps
  "Binaric chatter over a pentatonic, thinned so it reads as signal
  traffic rather than a melody."
  (scale :minor-pent :d5
         (with (degrade-by 0.3 (fast 2 (lsys binaric [0] 5)))
               :vel 0.45)))

;; ---- the arrangement ---------------------------------------------------

(def sections
  "Bars, not seconds. Each `:enter` only adds voices, mutes them, or
  redefines a pattern -- the tempo lives in `:bpm` so the thunks stay
  runnable with no transport, which is how the tests reach them."
  [{:name "The forge wakes"    :bars 4 :bpm 132
    :enter (fn [] (play! :boots #'boots))}

   {:name "Tankards"           :bars 4
    :enter (fn [] (play! :tankards #'tankards))}

   {:name "The organ"          :bars 8
    :enter (fn [] (play! :organ #'organ {:chan 0}))}

   {:name "The chorus"         :bars 8
    :enter (fn []
             (play! :chant #'chant {:chan 1})
             ;; The one deliberate redefinition. A voice holds the VAR,
             ;; so the render thread picks this up at the next cycle
             ;; edge with no transition machinery -- in a jam buffer you
             ;; would just retype the `def`.
             (alter-var-root #'boots (constantly full-stomp)))}

   {:name "Binaric breakdown"  :bars 8
    :enter (fn []
             (mute :organ)
             (mute :chant)
             (play! :beeps #'beeps {:chan 2}))}

   {:name "All hands"          :bars 8 :bpm 144
    :enter (fn [] (unmute :organ) (unmute :chant))}

   {:name "Powering down"      :bars 4 :bpm 120
    :enter (fn [] (mute :beeps) (mute :organ))}])

;; ---- running it --------------------------------------------------------

(defn- bars->ms
  "A cycle is four beats, and a bar is a cycle."
  [bars tempo]
  (long (/ (* bars 4 60000) (double tempo))))

(defn play-through!
  "Walk the arrangement once, then stop. Section boundaries are wall
  clock against the transport's own grid, so a change lands within a
  bar of where it is written -- close enough for a demo, and the
  cycle-edge swap itself is exact."
  [port]
  (println "\nmu -- a forge-tavern stomp. D aeolian.\n")
  (begin! {:port port :bpm 132})
  (loop [[{:keys [name bars enter] :as sect} & more] sections
         tempo 132]
    (if-not sect
      tempo
      (let [tempo (or (:bpm sect) tempo)]
        (bpm tempo)
        (enter)
        (println (format "  %-20s %2d bars  %3d bpm" name bars tempo))
        (Thread/sleep (bars->ms bars tempo))
        (recur more tempo))))
  (println "\n  hush\n")
  (hush)
  ;; Let the note-offs `panic!` sent actually leave the port.
  (Thread/sleep 500)
  (end!))

(defn -main
  "Optional argument is a MIDI port name, matched case-insensitively as
  a substring. With none, the first available port."
  [& [port]]
  (try
    (play-through! port)
    (finally
      (shutdown-agents))))
