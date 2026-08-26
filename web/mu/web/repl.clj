(ns mu.web.repl
  "A bridge from one browser tab to one nREPL session.

  Deliberately thin: nREPL already solves eval, stdout capture, exception
  formatting, completion and interrupt, and mu's live-coding model is
  nREPL's model -- redefine a var, the render thread picks it up next
  cycle. Reimplementing that here would only lose the ability to have
  an editor and a browser attached to the same process at once.

  One wrinkle nREPL doesn't solve for us: its session isolation covers
  dynamic bindings (*1, *2, *ns*, ...) but not the Namespace objects
  those bindings can point at. Two sessions that both eval into ns
  \"user\" share the very same `user` Namespace, so a `(def x 1)` in one
  is visible from the other. Since browser tabs must not share state,
  each session gets its own private namespace at connect time, and any
  eval/op that asks for the default \"user\" ns is quietly redirected
  there. An op that names some other, real namespace (e.g. one the
  render thread reads from) passes through untouched -- that sharing is
  the point of riding nREPL in the first place."
  (:require [nrepl.core :as nrepl]))

(defn connect!
  "Open a connection, allocate a session on it, and give the session a
  private namespace so separate browser tabs never see each other's defs."
  [{:keys [port host] :or {host "127.0.0.1"}}]
  (let [conn (nrepl/connect :host host :port port)]
    (try
      (let [client     (nrepl/client conn Long/MAX_VALUE)
            sess       (nrepl/new-session client)
            default-ns (symbol (str "web.session-" sess))]
        ;; `ns` (not `in-ns`) so clojure.core is referred, same as the
        ;; real `user` namespace has at boot.
        (dorun (nrepl/message client {:op "eval" :session sess
                                       :code (str "(ns " default-ns ")")}))
        {:conn conn :client client :session sess :default-ns default-ns})
      (catch Throwable t
        (.close ^java.io.Closeable conn)
        (throw t)))))

(defn- resolve-ns
  "Map the caller's notion of \"the default namespace\" onto this session's
  private one; pass any other namespace name through unchanged."
  [{:keys [default-ns]} ns]
  (if (or (nil? ns) (= ns "user"))
    (str default-ns)
    ns))

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
  [{:keys [client session] :as sess} id op-map on-frame]
  (let [op-map (cond-> op-map
                 (contains? op-map :ns) (update :ns #(resolve-ns sess %)))]
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
      (.start)))
  nil)

(defn eval!
  [session id code ns on-frame]
  (op! session id {:op "eval" :code code :ns (or ns "user")} on-frame))

(defn close! [{:keys [conn]}] (.close ^java.io.Closeable conn) nil)
