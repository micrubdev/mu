(ns mu.player
  "The voice registry and the live-coding seam.

  Voices hold VARS, not pattern values, and the render thread derefs
  them once per cycle. That single fact is the whole live-coding model:
  redefining a var lands on the next cycle boundary, in time, with no
  transition machinery."
  (:require [mu.clock :as clk]
            [mu.kit :as kit]
            [mu.midi :as midi]
            [mu.pattern :as p]))

(defonce ^:private !voices    (atom {}))
(defonce ^:private !muted     (atom #{}))
(defonce ^:private !soloed    (atom nil))
(defonce ^:private !last-good (atom {}))
(defonce ^:private !transport (atom nil))
(defonce ^:private !errors    (atom {}))

(defn voices [] @!voices)

(defn- resolve-pattern
  "Deref a var; pass a Pattern through unchanged."
  [x]
  (if (var? x) @x x))

(defn- apply-kit
  "Resolve a voice's drum names, defaulting to General MIDI.

  Applied here rather than in `play!` because a voice holds a VAR:
  wrapping at registration time would snapshot it and kill the
  redefinition model. A no-op on pitched patterns, so every voice can
  go through it unconditionally."
  [v pat]
  (if (p/pattern? pat)
    (kit/kit (get v :kit kit/gm) pat)
    pat))

(defn current-voices
  "The voices that should sound right now, with vars dereferenced, kits
  applied and mute/solo honoured. Called once per cycle by the render
  thread."
  []
  (let [solo @!soloed
        mute @!muted]
    (into {}
          (for [[k v] @!voices
                :when (if solo (= k solo) (not (mute k)))]
            [k (update v :pattern #(apply-kit v (resolve-pattern %)))]))))

(defn safe-render
  "Query one voice for one cycle, surviving anything the pattern does.

  On success the events are cached as that voice's last good cycle and any
  recorded error is cleared. On failure the error is reported to the REPL,
  recorded for the HUD, and the cached cycle is replayed, so a typo costs
  you the edit -- not the performance.

  Two arities: `(safe-render k cycle-n)` looks the voice up itself, for
  callers (tests, a REPL) that only have the key. `(safe-render k v
  cycle-n)` takes the voice map directly and is the shape
  `mu.clock/start!`'s `:render-voice` seam calls -- `mu.player/begin!`
  passes this function there directly, so a throwing pattern is caught
  and replayed on the real render thread, not just in a test that calls
  this function by hand."
  ([k cycle-n] (safe-render k (get (current-voices) k) cycle-n))
  ([k v cycle-n]
   (try
     (let [evs (doall (p/query (:pattern v) [cycle-n (inc cycle-n)]))]
       (swap! !last-good assoc k evs)
       (swap! !errors dissoc k)
       evs)
     (catch Throwable t
       (println (str "mu: voice " k " threw (" (.getMessage t)
                     ") -- replaying last good cycle"))
       (swap! !errors assoc k (str (.getMessage t)))
       (get @!last-good k [])))))

(defn play!
  "Register a voice. Prefer passing a VAR (#'bass) so redefinition lands
  automatically; a bare pattern is a snapshot and will not update."
  ([x] (play! (if (var? x) (keyword (name (.sym ^clojure.lang.Var x))) :default) x {}))
  ([k x] (play! k x {}))
  ([k x opts]
   (swap! !voices assoc k (merge {:pattern x :chan 0} opts))
   k))

(defn stop-voice! [k] (swap! !voices dissoc k) (swap! !last-good dissoc k) (swap! !errors dissoc k) k)

(defn hush
  "Stop every voice and silence anything sounding."
  []
  (reset! !voices {})
  (reset! !last-good {})
  (reset! !errors {})
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
  (reset! !last-good {}) (reset! !errors {})
  :reset)

(defn begin!
  "Open a MIDI port and start the transport."
  ([] (begin! {}))
  ([{:keys [port bpm] :or {bpm 120}}]
   (when @!transport (clk/stop! @!transport))
   (let [sink (midi/open-sink! port)]
     (reset! !transport (clk/start! {:sink sink
                                     :voices-fn current-voices
                                     :render-voice safe-render
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

(defn program!
  "Put channel `ch` on GM program `n` (0-127), now.

  `mu.midi` has no program change on the render path on purpose: a
  patch is a mode the channel is in, not an event in a pattern, and
  making it pattern data would mean tracking a last-program per channel
  on a render path that is deliberately pure. Set it once, from here.

  A no-op when the transport is stopped, like `panic`."
  [ch n]
  (when-not (<= 0 n 127)
    (throw (IllegalArgumentException. (str "program must be 0-127, got " n))))
  (when-let [t @!transport] (midi/send-now! (:sink t) {:type :program :chan ch :program n}))
  n)

(defn cc!
  "Send control change `n` (0-127) with value `v` (0-127) on channel
  `ch`, now. A no-op when the transport is stopped."
  [ch n v]
  (when-not (and (<= 0 n 127) (<= 0 v 127))
    (throw (IllegalArgumentException. (str "cc and value must be 0-127, got " n " " v))))
  (when-let [t @!transport] (midi/send-now! (:sink t) {:type :cc :chan ch :cc n :val v}))
  v)

(defn bpm [n]
  (when-let [t @!transport] (clk/set-bpm! t n))
  n)

(defn state
  "A full snapshot of what the performance looks like right now.

  A snapshot rather than a delta on purpose: the HUD feed drops cycles
  under load, and a consumer that receives whole states never has to
  reconstruct anything after a gap."
  []
  (let [solo @!soloed
        mute @!muted
        errs @!errors
        t    @!transport]
    {:playing? (boolean t)
     :bpm      (when t @(:bpm t))
     :voices   (into {}
                     (for [[k v] @!voices]
                       [k {:chan    (get v :chan 0)
                           :muted?  (contains? mute k)
                           :soloed? (= solo k)
                           :error   (get errs k)}]))}))
