(ns mu.web.repl
  "A bridge from one browser tab to one nREPL session.

  Deliberately thin: nREPL already solves eval, stdout capture, exception
  formatting, completion and interrupt, and mu's live-coding model is
  nREPL's model -- redefine a var, the render thread picks it up next
  cycle. Reimplementing that here would only lose the ability to have
  an editor and a browser attached to the same process at once.

  One session per tab exists so your Emacs session and the tab don't
  stomp each other's *1, *2, *ns*, and the like -- nREPL's own
  per-session dynamic bindings. It does not, and should not, isolate
  namespaces: a `(def bass ...)` evaluated from the browser must be
  visible to the render thread and to every other client attached to
  this same process. Sharing definitions across sessions is the whole
  point of the live-coding model, not a leak to guard against."
  (:require [nrepl.core :as nrepl]))

(def ^:private HANDSHAKE-TIMEOUT-MS
  "Bound on the initial new-session handshake only.

  A closed port fails instantly (ECONNREFUSED) regardless of this value.
  The case this guards is a port that is open but not actually nREPL --
  easy to hit by mistyping :nrepl-port next to the web port in the same
  docs -- which accepts the connection and then never sends anything
  back. With Long/MAX_VALUE that blocks whatever thread called connect!
  forever. Eval streams below keep the unbounded timeout: a bounded one
  there would truncate a long-running eval instead."
  5000)

(defn connect!
  "Open a connection and allocate a session on it."
  [{:keys [port host] :or {host "127.0.0.1"}}]
  (let [conn (nrepl/connect :host host :port port)]
    (try
      (let [sess   (nrepl/new-session (nrepl/client conn HANDSHAKE-TIMEOUT-MS))
            client (nrepl/client conn Long/MAX_VALUE)]
        {:conn conn :client client :session sess})
      (catch Throwable t
        (.close ^java.io.Closeable conn)
        (throw t)))))

(defn- frames
  "Turn one nREPL response map into zero or more wire frames."
  [id resp]
  (cond-> []
    (:out resp)   (conj {:t "out"   :id id :s (:out resp)})
    (:err resp)   (conj {:t "err"   :id id :s (:err resp)})
    (:value resp) (conj {:t "value" :id id :s (:value resp)})
    (:ex resp)    (conj {:t "ex"    :id id :s (:ex resp)})
    ;; Completion and info replies ride through as data rather than text.
    (:completions resp) (conj {:t "completions" :id id :items (:completions resp)})
    (:doc resp)   (conj {:t "doc"   :id id :s (:doc resp)})))

(defn op!
  "Send an nREPL op, streaming frames to `on-frame` on a background thread.

  Runs off-thread because the response seq blocks until the op finishes,
  and the caller is a socket handler that must stay responsive."
  [{:keys [client session]} id op-map on-frame]
  (doto (Thread.
          (fn []
            (try
              (doseq [resp (nrepl/message client (assoc op-map :session session))]
                (doseq [f (frames id resp)] (on-frame f))
                (when (some #{"done"} (:status resp))
                  (on-frame {:t "done" :id id})))
              (catch Throwable t
                (on-frame {:t "err" :id id :s (str "bridge error: " (.getMessage t))})
                (on-frame {:t "done" :id id})))))
    (.setDaemon true)
    (.start))
  nil)

(defn eval!
  [session id code ns on-frame]
  (op! session id {:op "eval" :code code :ns (or ns "user")} on-frame))

(defn close! [{:keys [conn]}] (.close ^java.io.Closeable conn) nil)
