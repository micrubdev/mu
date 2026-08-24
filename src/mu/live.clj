(ns mu.live
  "The performance namespace. Open a jam buffer with:

    (ns jam
      (:refer-clojure :exclude [rand])
      (:require [mu.live :refer :all]))

  Checked against clojure.core, the operator names here collide in
  exactly one place: `rand`. (`every?` is core; `every` is not.)"
  (:refer-clojure :exclude [rand])
  (:require [mu.notation]
            [mu.pattern]
            [mu.player]))

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
  mu.pattern/every    mu.pattern/degrade mu.pattern/degrade-by
  mu.pattern/sometimes mu.pattern/sometimes-by
  mu.pattern/sine     mu.pattern/saw     mu.pattern/tri  mu.pattern/rand
  mu.pattern/fmap     mu.pattern/with
  ;; player
  mu.player/play!     mu.player/stop-voice!
  mu.player/hush      mu.player/panic
  mu.player/mute      mu.player/unmute
  mu.player/solo      mu.player/unsolo
  mu.player/begin!    mu.player/end!     mu.player/bpm
  mu.player/voices)
