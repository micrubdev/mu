(ns mu.live
  "The performance namespace. Open a jam buffer with:

    (ns jam
      (:refer-clojure :exclude [rand])
      (:require [mu.live :refer :all]))

  Checked against clojure.core, the operator names here collide in
  exactly one place: `rand`. (`every?` is core; `every` is not.)"
  (:refer-clojure :exclude [rand])
  (:require [mu.harmony]
            [mu.notation]
            [mu.pattern]
            [mu.player]
            [mu.transform]))

(defmacro ^:private import-vars
  "Re-export vars from other namespaces, preserving metadata -- crucially
  including :macro, without which `notes` would break."
  [& syms]
  `(do
     ~@(for [s syms]
         `(let [v# (resolve '~s)]
            (intern *ns* (with-meta '~(symbol (name s)) (meta v#)) @v#)
            (when (:macro (meta v#))
              (.setMacro ^clojure.lang.Var (resolve '~(symbol (name s)))))))))

(import-vars
  ;; notation
  mu.notation/notes
  mu.notation/note-name->midi
  ;; algebra
  mu.pattern/silence  mu.pattern/pure    mu.pattern/query
  mu.pattern/stack    mu.pattern/slowcat mu.pattern/fastcat
  mu.pattern/cyc      mu.pattern/sub
  mu.pattern/fast     mu.pattern/slow
  mu.pattern/early    mu.pattern/late    mu.pattern/rev
  mu.transform/every  mu.transform/degrade mu.transform/degrade-by
  mu.transform/sometimes mu.transform/sometimes-by
  mu.pattern/sine     mu.pattern/saw     mu.pattern/tri  mu.pattern/rand
  mu.transform/euclid mu.transform/off   mu.transform/superimpose
  mu.transform/euclid-full  mu.transform/arp
  mu.transform/iter   mu.transform/stut
  mu.pattern/fmap     mu.pattern/with
  ;; harmony
  mu.harmony/scale    mu.harmony/chord
  ;; player
  mu.player/play!     mu.player/stop-voice!
  mu.player/hush      mu.player/panic
  mu.player/mute      mu.player/unmute
  mu.player/solo      mu.player/unsolo
  mu.player/begin!    mu.player/end!     mu.player/bpm
  mu.player/voices)

(defn web!
  "Start the browser view. Requires the :web alias -- mu's core carries
  no dependencies, so the web namespaces are not on the default
  classpath. See README."
  [& args]
  (if-let [v (try (requiring-resolve 'mu.web/web!)
                  (catch Throwable _ nil))]
    (apply v args)
    (println "mu: the web view needs the :web alias. Start with"
             "`clojure -M:web`.")))

(defn web-off! []
  (when-let [v (try (requiring-resolve 'mu.web/web-off!)
                    (catch Throwable _ nil))]
    (v)))
