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

  TIMBRE. The melodic channels set their own GM patches with
  `program!` before the first bar, so this sounds as written on any
  General MIDI device, Gervill included. A synth that ignores program
  change will play it all on whatever it is already set to."
  (:refer-clojure :exclude [rand])
  (:require [mu.live :refer :all]))

;; ---- the parts ---------------------------------------------------------

(def ^:private stomp
  "Two dotted-quarter stomps to the bar. The whole piece leans on this."
  (notes :bd _ _ :bd _ _))

(def ^:private fill
  "Boots coming down the ladder."
  (notes :ht :mt :ft :lt))

(def ^:private boot-level
  "One number for both boots patterns, because they replace one another
  mid-piece. When only `boots` carried a velocity, the swap at the
  chorus took it away and the drums jumped from a mean of 59 to 74 --
  louder for the whole back half, at exactly the moment the chant most
  needs the room. A shared constant is the fix that cannot drift."
  0.58)

(def boots
  "A four-bar phrase: three bars of stomping, then half a bar of it with
  a tom spill on the back half.

  Held well under the melodic voices: a GM bass drum at full velocity
  buries everything above it."
  (with (cyc stomp stomp stomp (sub stomp fill)) :vel boot-level))

(def ^:private full-stomp
  "What the boots become once the chorus starts -- the gap on beat two
  fills in and the phrase ends on a floor tom."
  (with (cyc (notes :bd _ :bd :bd _ :ft)
             (notes :bd _ :bd :bd _ :ft)
             (notes :bd _ :bd :bd _ :ft)
             (sub (notes :bd _ :bd :bd _ :ft) fill))
        :vel boot-level))

(def tankards
  "Tankards struck on the table: five in six, accented by a velocity
  pattern that runs at its own rate, and thinned so it stays a room
  full of people rather than a machine."
  (degrade-by 0.25
              (stack (with (euclid 5 6 (notes :tb))
                           :vel (sub 0.5 0.3 0.4 0.28 0.46 0.32))
                     ;; The cowbell is the most piercing thing in the GM
                     ;; kit; it marks the bar rather than competing.
                     (with (late 1/6 (euclid 2 6 (notes :cb))) :vel 0.32))))

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
  "Block chords, broken into a rolling arpeggio on alternate bars.

  It used to carry an octave stop as well. Six notes to a chord left no
  room for the chorus, so the organ gives the octaves up -- the chant is
  the voice that wants them.

  Rooted at d4 rather than d3. At d3 it ran 50-67 while the chant's
  doubled upper voice ran to 57, and the two shared pitches 50, 53 and
  57 outright: two sustained voices on the same notes in the same
  octave, which is mud no velocity can fix. An octave up clears it."
  (scale :aeolian :d4
         (with (every 2 #(arp :up %) progression) :vel 0.55)))

(def chant
  "The roaring chorus: one low modal line, doubled at the octave, two
  bars long."
  (scale :aeolian :d2
         (with (superimpose octave-up (slow 2 (notes 0 2 3 4 _ 4 3 2)))
               ;; Choir aahs is a soft patch and this is the tune; it
               ;; needs to sit on top of everything else.
               :vel 0.8)))

(def ^:private binaric
  "The machine's own grammar. Three symbols, so the chatter has three
  pitches; it grows about 1.7x a generation, nowhere near the cap."
  {0 [0 2], 2 [0 1], 1 [2]})

(def beeps
  "Binaric chatter over a pentatonic, thinned so it reads as signal
  traffic rather than a melody."
  ;; d6, not d5: the organ's new top reaches 79 and the chatter used to
  ;; sit at 74-79, right on it. Up here it is unmistakably a machine.
  (scale :minor-pent :d6
         (with (degrade-by 0.15 (fast 2 (lsys binaric [0] 5)))
               :vel 0.4)))

;; ---- patches -----------------------------------------------------------

(def patches
  "GM programs for the three melodic channels; 9 is percussion and
  needs none. A patch is a mode the channel is in rather than an event
  in a pattern, which is why these are sent once by `play-through!` and
  do not appear in any pattern above."
  {0 18    ; rock organ
   1 52    ; choir aahs -- the shipboard chorus
   2 80})  ; square lead -- the machine beeps

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

   ;; The organ drops but the chant does not: muting both left the
   ;; breakdown with nothing sustained under the chatter, and it sagged.
   {:name "Binaric breakdown"  :bars 6
    :enter (fn []
             (mute :organ)
             (play! :beeps #'beeps {:chan 2}))}

   {:name "All hands"          :bars 8 :bpm 152
    :enter (fn [] (unmute :organ))}

   ;; Six bars and a bigger tempo drop, so it winds down rather than
   ;; stopping.
   {:name "Powering down"      :bars 6 :bpm 112
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
  (doseq [[ch prog] patches] (program! ch prog))
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
