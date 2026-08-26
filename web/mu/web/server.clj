(ns mu.web.server
  "HTTP and two WebSockets: /hud broadcasts, /repl bridges.

  One broadcaster thread drains the tap and writes to every connected
  HUD client. It exists so that a slow socket write happens here and not
  on the render thread; the tap between them drops rather than blocks,
  which is what keeps a stalled browser from ever touching the clock.

  All mutable state (`!hud-clients`, `!repl-sessions`, `running?`) is
  created fresh in `start!` and closed over by that instance's handlers,
  rather than held in module-level atoms -- two servers in one JVM (as
  the test suite runs, back to back) must not cross-wire: stopping one
  must never touch another's clients or sessions."
  (:require [mu.player :as player]
            [mu.tap :as tap]
            [mu.web.protocol :as proto]
            [mu.web.repl :as repl]
            [org.httpkit.server :as hk])
  (:import [java.io File]))

(defn- broadcast! [!hud-clients ^String s]
  (doseq [ch @!hud-clients]
    ;; send! returns false on a closed channel rather than throwing (see
    ;; the Channel protocol docstring); this catch is belt-and-braces
    ;; insurance against any lower-level I/O exception, not a compensation
    ;; for a documented throw.
    (try (hk/send! ch s)
         (catch Throwable _ (swap! !hud-clients disj ch)))))

(defn- broadcaster
  "Drain the tap, encode, broadcast. One thread, never on the clock's path.

  Each iteration is individually guarded: a bad cycle (a broken encode, a
  `player/state` throw) must cost one broadcast, not the rest of the
  process's HUD feed -- this thread's whole job is to survive whatever the
  web side throws at it."
  [running? !hud-clients]
  (let [t (tap/subscribe!)]
    (doto (Thread.
            (fn []
              (try
                (while @running?
                  (try
                    (when-let [c (tap/poll! t 200)]
                      (when (seq @!hud-clients)
                        (broadcast! !hud-clients (proto/encode (proto/cycle-msg c (player/state))))))
                    (catch Throwable e
                      (println "mu.web.server: broadcaster iteration failed:" (.getMessage e)))))
                (finally (tap/unsubscribe! t)))))
      (.setDaemon true)
      (.setName "mu-web-broadcaster")
      (.start))))

(defn- on-hud-frame [ch raw]
  (when-let [m (proto/decode raw)]
    (when (= "ping" (:t m))
      ;; Take nanoTime as late as possible: the closer to the write, the
      ;; smaller the asymmetry the client's min-RTT estimator has to eat.
      ;; Guarded the same as /repl's send: a closed channel is a normal
      ;; race, not a bug worth surfacing.
      (try (hk/send! ch (proto/encode (proto/pong-msg (:id m) (:c m) (System/nanoTime))))
           (catch Throwable _ nil)))))

(defn- hud-handler [!hud-clients req]
  (hk/as-channel req
    {:on-open    (fn [ch] (swap! !hud-clients conj ch))
     :on-close   (fn [ch _] (swap! !hud-clients disj ch))
     :on-receive (fn [ch raw] (on-hud-frame ch raw))}))

(defn- get-session!
  "Get or lazily create the one nREPL session for this channel.

  `swap!` retries under contention, so `repl/connect!` -- which opens a
  real socket -- must never run as the body of the update fn; that would
  open one connection per retry. A `delay` sidesteps this: constructing
  one is side-effect free, so it is safe inside `swap!`, and no matter how
  many `:on-receive` callbacks race here concurrently for the same
  channel, only the winning delay is ever installed, and only that one is
  ever dereffed.

  A `delay` caches a thrown exception exactly as readily as a value: once
  `connect!` fails, `realized?` is true forever after and every later
  deref rethrows that same stale failure with no retry. So a failed deref
  evicts its own entry here -- but only if it is still the very delay this
  call installed or found, so a concurrent winner's healthy entry is never
  clobbered -- and lets the caller handle the failure. The next frame then
  starts over with a fresh delay instead of the cached exception."
  [!repl-sessions nrepl-port ch]
  (let [d (-> (swap! !repl-sessions update ch #(or % (delay (repl/connect! {:port nrepl-port}))))
              (get ch))]
    (try
      (let [s @d]
        ;; The channel closed while this connect was still in flight:
        ;; :on-close saw `realized?` false and skipped the close, but
        ;; dissoc'd anyway, so nothing else holds this session. Close it
        ;; here instead of leaking a socket and a session thread for the
        ;; life of the process.
        (when-not (identical? (get @!repl-sessions ch) d)
          (try (repl/close! s) (catch Throwable _ nil)))
        s)
      (catch Throwable t
        (swap! !repl-sessions (fn [m] (if (identical? (get m ch) d) (dissoc m ch) m)))
        (throw t)))))

(defn- on-repl-frame [!repl-sessions nrepl-port ch raw]
  (when-let [{:keys [t id code ns prefix]} (proto/decode raw)]
    (let [send (fn [f] (try (hk/send! ch (proto/encode f)) (catch Throwable _ nil)))]
      ;; get-session! can block on a real socket -- bounded now (see
      ;; repl/connect!'s handshake timeout), but still not something an
      ;; http-kit worker thread should sit on. http-kit's pool is small
      ;; (4 threads by default), so run the whole dispatch, including the
      ;; session lookup, on its own thread rather than tying up a worker
      ;; for the length of a connect attempt. op! already does the same
      ;; for the op itself; this just moves the lookup off-thread too.
      (doto (Thread.
              (fn []
                (try
                  (let [sess (get-session! !repl-sessions nrepl-port ch)]
                    (case t
                      "eval"      (repl/eval! sess id code ns send)
                      "complete"  (repl/op! sess id {:op "completions" :prefix prefix :ns (or ns "user")} send)
                      "info"      (repl/op! sess id {:op "lookup" :sym prefix :ns (or ns "user")} send)
                      "interrupt" (repl/op! sess id {:op "interrupt"} send)
                      nil))
                  (catch Throwable e
                    ;; The nREPL this tab bridges to could not be reached
                    ;; (or the session otherwise failed to open). Answer
                    ;; the browser the way every other failure path does
                    ;; -- err then done, carrying the request id -- rather
                    ;; than letting the exception unwind in silence.
                    (send {:t "err" :id id :s (str "repl bridge: " (.getMessage e))})
                    (send {:t "done" :id id})))))
        (.setDaemon true)
        (.start)))))

(defn- close-session!
  "Close a session if it ever actually opened. Never throws: a poisoned or
  half-open session must not be able to prevent a shutdown. `realized?` is
  true for a poisoned delay too (the exception is cached the same as a
  value would be) so the deref inside this try is what actually needs the
  guard, not just the close."
  [d]
  (when (realized? d)
    (try (repl/close! @d) (catch Throwable _ nil))))

(defn- repl-handler [!repl-sessions nrepl-port req]
  (hk/as-channel req
    {;; Read-and-remove atomically: whether http-kit serialises :on-close
     ;; against :on-receive for one channel is unconfirmed, so a plain
     ;; get-then-dissoc could act on a map already changed by a racing
     ;; get-session!. swap-vals! makes the read and the removal one step.
     :on-close   (fn [ch _]
                   (let [[old _] (swap-vals! !repl-sessions dissoc ch)]
                     (when-let [d (get old ch)] (close-session! d))))
     :on-receive (fn [ch raw] (on-repl-frame !repl-sessions nrepl-port ch raw))}))

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
  ;; Lower-cased: an extension's case carries no meaning on the wire, but
  ;; a bare .lastIndexOf lookup is case-sensitive, so "/App.JS" fell
  ;; through to octet-stream -- the exact browser refusal this map exists
  ;; to prevent.
  (let [ext (-> (subs path (inc (.lastIndexOf path ".")))
                (.toLowerCase))]
    (get CONTENT-TYPES ext "application/octet-stream")))

(defn- static [root uri]
  (let [uri      (if (= "/" uri) "/index.html" uri)
        ;; Reject traversal before touching the filesystem. Kept as a
        ;; first, cheap rejection, but not trusted alone -- http-kit's own
        ;; client normalises ".." out of a URI before it ever reaches this
        ;; code, so a test against this string alone can pass without the
        ;; guard ever doing anything. The canonical-path containment check
        ;; below is what actually matters against a raw request that
        ;; skips URI normalisation.
        safe     (.replaceAll ^String uri "\\.\\." "")
        root-file (File. ^String root)
        f        (File. (str root safe))]
    (if (and (.isFile f)
             (.startsWith (.getCanonicalPath f) (.getCanonicalPath root-file)))
      {:status 200
       :headers {"Content-Type" (content-type safe)}
       :body f}
      {:status 404 :body "not found"})))

(defn- router [{:keys [root nrepl-port !hud-clients !repl-sessions]}]
  (fn [{:keys [uri] :as req}]
    (case uri
      "/hud"  (hud-handler !hud-clients req)
      "/repl" (if nrepl-port
                (repl-handler !repl-sessions nrepl-port req)
                {:status 503 :body "no nrepl port configured"})
      (static root uri))))

(defn start!
  "Start the web view. Returns a map to pass to stop!.

  :port       HTTP/WebSocket port (0 picks a free one)
  :nrepl-port the port this process's own nREPL server is on
  :root       directory of built client assets
  :ip         interface to bind, default \"127.0.0.1\". This HTTP server
              proxies straight into an unauthenticated nREPL eval bridge,
              so binding it to a real interface (\"0.0.0.0\", a LAN address)
              hands arbitrary code execution to anyone who can reach that
              interface -- only pass one deliberately, for a trusted
              network, with a performer who understands the risk."
  [{:keys [port nrepl-port root ip] :or {port 7890 root "client/dist" ip "127.0.0.1"} :as opts}]
  (let [running?       (atom true)
        !hud-clients   (atom #{})
        !repl-sessions (atom {})
        srv (hk/run-server (router (assoc opts
                                          :root root
                                          :!hud-clients !hud-clients
                                          :!repl-sessions !repl-sessions))
                           {:port port :ip ip :legacy-return-value? false})]
    (broadcaster running? !hud-clients)
    {:running?       running?
     :srv            srv
     :port           (hk/server-port srv)
     :!hud-clients   !hud-clients
     :!repl-sessions !repl-sessions}))

(defn stop! [{:keys [running? srv !hud-clients !repl-sessions]}]
  (reset! running? false)
  ;; close-session! never throws, so a poisoned or half-open session in the
  ;; map cannot abort this cleanup before the atoms are reset and the HTTP
  ;; server is actually released.
  (doseq [[_ d] @!repl-sessions] (close-session! d))
  (reset! !repl-sessions {})
  (reset! !hud-clients #{})
  ;; Wait for the port to actually be released -- discarding the promise
  ;; here would let a caller (mu.web/web!) rebind the same fixed port
  ;; before the old listener is gone, surfacing as a BindException mid-set.
  @(hk/server-stop! srv {:timeout 1000})
  nil)
