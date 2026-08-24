(ns mu.player
  "The voice registry and the live-coding seam.

  Voices hold VARS, not pattern values, and the render thread derefs
  them once per cycle. That single fact is the whole live-coding model:
  redefining a var lands on the next cycle boundary, in time, with no
  transition machinery."
  (:require [mu.clock :as clk]
            [mu.midi :as midi]
            [mu.pattern :as p]))

(defonce ^:private !voices    (atom {}))
(defonce ^:private !muted     (atom #{}))
(defonce ^:private !soloed    (atom nil))
(defonce ^:private !last-good (atom {}))
(defonce ^:private !transport (atom nil))

(defn voices [] @!voices)

(defn- resolve-pattern
  "Deref a var; pass a Pattern through unchanged."
  [x]
  (if (var? x) @x x))

(defn current-voices
  "The voices that should sound right now, with vars dereferenced and
  mute/solo applied. Called once per cycle by the render thread."
  []
  (let [solo @!soloed
        mute @!muted]
    (into {}
          (for [[k v] @!voices
                :when (if solo (= k solo) (not (mute k)))]
            [k (update v :pattern resolve-pattern)]))))

(defn safe-render
  "Query one voice for one cycle, surviving anything the pattern does.

  On success the events are cached as that voice's last good cycle. On
  failure the error is reported to the REPL and the cached cycle is
  replayed, so a typo costs you the edit -- not the performance."
  [k cycle-n]
  (let [v (get (current-voices) k)]
    (try
      (let [evs (doall (p/query (:pattern v) [cycle-n (inc cycle-n)]))]
        (swap! !last-good assoc k evs)
        evs)
      (catch Throwable t
        (println (str "mu: voice " k " threw (" (.getMessage t)
                      ") -- replaying last good cycle"))
        (get @!last-good k [])))))

(defn play!
  "Register a voice. Prefer passing a VAR (#'bass) so redefinition lands
  automatically; a bare pattern is a snapshot and will not update."
  ([x] (play! (if (var? x) (keyword (name (.sym ^clojure.lang.Var x))) :default) x {}))
  ([k x] (play! k x {}))
  ([k x opts]
   (swap! !voices assoc k (merge {:pattern x :chan 0} opts))
   k))

(defn stop-voice! [k] (swap! !voices dissoc k) (swap! !last-good dissoc k) k)

(defn hush
  "Stop every voice and silence anything sounding."
  []
  (reset! !voices {})
  (reset! !last-good {})
  (when-let [t @!transport] (clk/panic! t))
  :hushed)

(defn panic
  "Emergency silence: all-notes-off everywhere, voices left registered."
  []
  (when-let [t @!transport] (clk/panic! t))
  :panicked)

(defn mute   [k] (swap! !muted conj k) k)
(defn unmute [k] (swap! !muted disj k) k)
(defn solo   [k] (reset! !soloed k) k)
(defn unsolo []  (reset! !soloed nil) :all)

(defn reset-all!
  "Clear all registry state. Used by tests and between sessions."
  []
  (reset! !voices {}) (reset! !muted #{}) (reset! !soloed nil)
  (reset! !last-good {})
  :reset)

(defn begin!
  "Open a MIDI port and start the transport."
  ([] (begin! {}))
  ([{:keys [port bpm] :or {bpm 120}}]
   (when @!transport (clk/stop! @!transport))
   (let [sink (midi/open-sink! port)]
     (reset! !transport (clk/start! {:sink sink
                                     :voices-fn current-voices
                                     :bpm bpm}))
     :playing)))

(defn end!
  "Stop the transport and close the port."
  []
  (when-let [t @!transport]
    (clk/stop! t)
    (midi/close-sink! (:sink t))
    (reset! !transport nil))
  :stopped)

(defn bpm [n]
  (when-let [t @!transport] (clk/set-bpm! t n))
  n)
