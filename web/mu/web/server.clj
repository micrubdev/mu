(ns mu.web.server
  "HTTP and two WebSockets: /hud broadcasts, /repl bridges.

  One broadcaster thread drains the tap and writes to every connected
  HUD client. It exists so that a slow socket write happens here and not
  on the render thread; the tap between them drops rather than blocks,
  which is what keeps a stalled browser from ever touching the clock."
  (:require [mu.player :as player]
            [mu.tap :as tap]
            [mu.web.protocol :as proto]
            [mu.web.repl :as repl]
            [org.httpkit.server :as hk])
  (:import [java.io File]))

(defonce ^:private !hud-clients (atom #{}))
(defonce ^:private !repl-sessions (atom {}))

(defn- broadcast! [^String s]
  (doseq [ch @!hud-clients]
    (try (hk/send! ch s)
         (catch Throwable _ (swap! !hud-clients disj ch)))))

(defn- broadcaster
  "Drain the tap, encode, broadcast. One thread, never on the clock's path."
  [running?]
  (let [t (tap/subscribe!)]
    (doto (Thread.
            (fn []
              (try
                (while @running?
                  (when-let [c (tap/poll! t 200)]
                    (when (seq @!hud-clients)
                      (broadcast! (proto/encode (proto/cycle-msg c (player/state)))))))
                (finally (tap/unsubscribe! t)))))
      (.setDaemon true)
      (.setName "mu-web-broadcaster")
      (.start))))

(defn- on-hud-frame [ch raw]
  (when-let [m (proto/decode raw)]
    (when (= "ping" (:t m))
      ;; Take nanoTime as late as possible: the closer to the write, the
      ;; smaller the asymmetry the client's min-RTT estimator has to eat.
      (hk/send! ch (proto/encode (proto/pong-msg (:id m) (:c m) (System/nanoTime)))))))

(defn- hud-handler [req]
  (hk/as-channel req
    {:on-open    (fn [ch] (swap! !hud-clients conj ch))
     :on-close   (fn [ch _] (swap! !hud-clients disj ch))
     :on-receive (fn [ch raw] (on-hud-frame ch raw))}))

(defn- on-repl-frame [nrepl-port ch raw]
  (when-let [{:keys [t id code ns op prefix] :as m} (proto/decode raw)]
    (let [sess (or (get @!repl-sessions ch)
                   (let [s (repl/connect! {:port nrepl-port})]
                     (swap! !repl-sessions assoc ch s)
                     s))
          send (fn [f] (try (hk/send! ch (proto/encode f)) (catch Throwable _ nil)))]
      (case t
        "eval"      (repl/eval! sess id code ns send)
        "complete"  (repl/op! sess id {:op "completions" :prefix prefix :ns (or ns "user")} send)
        "info"      (repl/op! sess id {:op "lookup" :sym prefix :ns (or ns "user")} send)
        "interrupt" (repl/op! sess id {:op "interrupt"} send)
        nil))))

(defn- repl-handler [nrepl-port req]
  (hk/as-channel req
    {:on-close   (fn [ch _]
                   (when-let [s (get @!repl-sessions ch)] (repl/close! s))
                   (swap! !repl-sessions dissoc ch))
     :on-receive (fn [ch raw] (on-repl-frame nrepl-port ch raw))}))

(def ^:private CONTENT-TYPES
  ;; Browsers refuse to execute an ES module served as text/plain, so this
  ;; map is load-bearing, not a nicety.
  {"html" "text/html; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "wasm" "application/wasm"
   "sf2"  "application/octet-stream"
   "map"  "application/json"})

(defn- content-type [^String path]
  (let [ext (subs path (inc (.lastIndexOf path ".")))]
    (get CONTENT-TYPES ext "application/octet-stream")))

(defn- static [root uri]
  (let [uri  (if (= "/" uri) "/index.html" uri)
        ;; Reject traversal before touching the filesystem.
        safe (.replaceAll ^String uri "\\.\\." "")
        f    (File. (str root safe))]
    (if (.isFile f)
      {:status 200
       :headers {"Content-Type" (content-type safe)}
       :body f}
      {:status 404 :body "not found"})))

(defn- router [{:keys [root nrepl-port]}]
  (fn [{:keys [uri] :as req}]
    (case uri
      "/hud"  (hud-handler req)
      "/repl" (if nrepl-port
                (repl-handler nrepl-port req)
                {:status 503 :body "no nrepl port configured"})
      (static root uri))))

(defn start!
  "Start the web view. Returns a map to pass to stop!.

  :port       HTTP/WebSocket port (0 picks a free one)
  :nrepl-port the port this process's own nREPL server is on
  :root       directory of built client assets"
  [{:keys [port nrepl-port root] :or {port 7890 root "client/dist"} :as opts}]
  (let [running? (atom true)
        stop-fn  (hk/run-server (router (assoc opts :root root))
                                {:port port :legacy-return-value? false})]
    (broadcaster running?)
    {:running? running? :stop-fn stop-fn :port (hk/server-port stop-fn)}))

(defn stop! [{:keys [running? stop-fn]}]
  (reset! running? false)
  (doseq [[_ s] @!repl-sessions] (repl/close! s))
  (reset! !repl-sessions {})
  (reset! !hud-clients #{})
  (hk/server-stop! stop-fn)
  nil)
