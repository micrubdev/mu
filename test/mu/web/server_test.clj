(ns mu.web.server-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [mu.notation :refer [notes]]
            [mu.player :as pl]
            [mu.tap :as tap]
            [mu.web.protocol :as proto]
            [mu.web.repl :as repl]
            [mu.web.server :as server]
            [nrepl.server :as nrepl-server]
            [org.httpkit.client :as http])
  (:import [java.net ServerSocket URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent TimeUnit]))

;; http-kit 2.8.0's org.httpkit.client namespace has no WebSocket client at
;; all -- only get/post/etc for plain HTTP. So the WebSocket half of these
;; tests drives a real socket with the JDK's own java.net.http.WebSocket
;; (built in since Java 11, no extra dependency needed). The plain-HTTP
;; tests below still use http-kit's client, which does have `get`.

(defn- with-server [f]
  (tap/reset-all!)
  (pl/reset-all!)
  (let [s (server/start! {:port 0 :nrepl-port nil :root "client/dist"})]
    (try (f s) (finally (server/stop! s) (tap/reset-all!)))))

(defn- with-nrepl
  "Start a real in-process nREPL server, run f with its port, stop it."
  [f]
  (let [server (nrepl-server/start-server :port 0)]
    (try (f (:port server))
         (finally (nrepl-server/stop-server server)))))

(defn- with-repl-server
  "Start mu's web server wired to a real in-process nREPL so /repl has
  something to bridge to, run f with the server map, then tear both down."
  [f]
  (tap/reset-all!)
  (pl/reset-all!)
  (with-nrepl
    (fn [nrepl-port]
      (let [s (server/start! {:port 0 :nrepl-port nrepl-port :root "client/dist"})]
        (try (f s) (finally (server/stop! s) (tap/reset-all!)))))))

(defn- unused-port
  "A port nothing is listening on, for testing an unreachable nREPL. Bind
  to port 0, read back what the OS picked, then release it immediately --
  good enough for a test's narrow window, not a general-purpose reservation."
  []
  (with-open [ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(defn- with-server-nrepl-port
  "Like with-repl-server, but points :nrepl-port at a caller-chosen port
  instead of spinning up a real nREPL -- for exercising what happens when
  that port has nothing listening on it."
  [nrepl-port f]
  (tap/reset-all!)
  (pl/reset-all!)
  (let [s (server/start! {:port 0 :nrepl-port nrepl-port :root "client/dist"})]
    (try (f s) (finally (server/stop! s) (tap/reset-all!)))))

(defn- ws-connect
  "Open a real WebSocket, collecting decoded frames into an atom. Returns
  the connected java.net.http.WebSocket, or nil if it failed to open
  within 2s."
  [port path !frames]
  (let [uri (URI/create (str "ws://127.0.0.1:" port path))
        listener (reify WebSocket$Listener
                   (onText [_ ws data last]
                     (swap! !frames conj (proto/decode (str data)))
                     (.request ^WebSocket ws 1)
                     nil))]
    (try
      (-> (HttpClient/newHttpClient)
          (.newWebSocketBuilder)
          (.buildAsync uri listener)
          (.get 2 TimeUnit/SECONDS))
      (catch Exception _ nil))))

(defn- ws-send! [^WebSocket ws ^String msg]
  (.get (.sendText ws msg true) 2 TimeUnit/SECONDS))

(defn- ws-close!
  "Abruptly tear down the connection -- no close handshake -- to exercise
  the server's on-close path the way a browser tab going away would."
  [^WebSocket ws]
  (.abort ws))

(defn- wait-for
  "Poll `pred` on the frames atom for up to 3s. Returns the matching frame."
  [!frames pred]
  (loop [n 0]
    (or (first (filter pred @!frames))
        (when (< n 300) (Thread/sleep 10) (recur (inc n))))))

(deftest a-hud-client-receives-published-cycles
  (with-server
    (fn [s]
      (let [!f (atom [])]
        (ws-connect (:port s) "/hud" !f)
        (pl/play! :bass (notes c2) {:chan 0})
        (tap/publish! {:cycle 7 :t0 100 :npc 200
                       :events [{:at 100 :spec {:type :note-on :chan 0
                                                :note 36 :vel 0.8}}]})
        (let [m (wait-for !f #(= "cycle" (:t %)))]
          (is (= 7 (:n m)))
          (is (= "100" (:t0 m)))
          (is (= 0 (-> m :msgs first :d)))
          (is (= 36 (-> m :msgs first :note)))
          (is (= {:chan 0 :muted false :soloed false :error nil}
                 (get-in m [:voices :bass]))))))))

(deftest a-ping-is-answered-with-a-pong-carrying-both-clocks
  (with-server
    (fn [s]
      (let [!f (atom [])
            ch (ws-connect (:port s) "/hud" !f)]
        (ws-send! ch (proto/encode {:t "ping" :id 3 :c 12.5}))
        (let [m (wait-for !f #(= "pong" (:t %)))]
          (is (= 3 (:id m)))
          (is (= 12.5 (:c m)) "the client's own clock comes back untouched")
          (is (pos? (Long/parseLong (:s m))) "the server's nanoTime is attached"))))))

(deftest publishing-with-no-hud-clients-does-not-throw
  (with-server
    (fn [_]
      (is (nil? (tap/publish! {:cycle 1 :t0 1 :npc 1 :events []}))))))

(deftest a-disconnected-client-stops-receiving-and-the-server-survives
  (with-server
    (fn [s]
      (let [!f (atom [])
            ch (ws-connect (:port s) "/hud" !f)]
        (ws-close! ch)
        (Thread/sleep 100)
        (tap/publish! {:cycle 9 :t0 1 :npc 1 :events []})
        (Thread/sleep 100)
        (is (nil? (wait-for !f #(= 9 (:n %)))))))))

(deftest static-files-are-served-with-a-usable-content-type
  (testing "an ES module served as text/plain is refused by every browser"
    (with-server
      (fn [s]
        (spit "client/dist/probe.js" "export const x = 1")
        (try
          (let [resp @(http/get (str "http://127.0.0.1:" (:port s) "/probe.js"))]
            (is (= 200 (:status resp)))
            (is (re-find #"text/javascript" (get-in resp [:headers :content-type]))))
          (finally (io/delete-file "client/dist/probe.js" true)))))))

(deftest path-traversal-is-refused
  (with-server
    (fn [s]
      (let [resp @(http/get (str "http://127.0.0.1:" (:port s) "/../../deps.edn"))]
        (is (= 404 (:status resp)))))))

(deftest unknown-frames-are-ignored
  (with-server
    (fn [s]
      (let [!f (atom [])
            ch (ws-connect (:port s) "/hud" !f)]
        (ws-send! ch "not json at all")
        (ws-send! ch (proto/encode {:t "ping" :id 1 :c 1.0}))
        (is (some? (wait-for !f #(= "pong" (:t %))))
            "the connection survived the garbage frame")))))

;; /repl: bridges eval traffic to a real nREPL, one session per channel.

(deftest a-repl-eval-returns-value-then-done-with-the-request-id
  (with-repl-server
    (fn [s]
      (let [!f (atom [])
            ch (ws-connect (:port s) "/repl" !f)]
        (ws-send! ch (proto/encode {:t "eval" :id "r1" :code "(+ 1 1)" :ns "user"}))
        (is (some? (wait-for !f #(= "done" (:t %)))))
        (is (= "2" (->> @!f (filter #(= "value" (:t %))) first :s)))
        (is (every? #(= "r1" (:id %)) @!f) "every frame carries the request id")))))

(deftest two-repl-channels-get-two-distinct-sessions
  ;; If both channels landed on the same nREPL session, channel a's *1
  ;; would see whatever channel b evaluated last -- so this only passes
  ;; when each channel's dynamic state (*1) is genuinely its own.
  (with-repl-server
    (fn [s]
      (let [!fa (atom [])
            !fb (atom [])
            a   (ws-connect (:port s) "/repl" !fa)
            b   (ws-connect (:port s) "/repl" !fb)]
        (ws-send! a (proto/encode {:t "eval" :id "a1" :code "100" :ns "user"}))
        (is (some? (wait-for !fa #(= "done" (:t %)))))
        (ws-send! b (proto/encode {:t "eval" :id "b1" :code "200" :ns "user"}))
        (is (some? (wait-for !fb #(= "done" (:t %)))))
        (ws-send! a (proto/encode {:t "eval" :id "a2" :code "*1" :ns "user"}))
        (is (some? (wait-for !fa #(and (= "done" (:t %)) (= "a2" (:id %))))))
        (ws-send! b (proto/encode {:t "eval" :id "b2" :code "*1" :ns "user"}))
        (is (some? (wait-for !fb #(and (= "done" (:t %)) (= "b2" (:id %))))))
        (is (= "100" (->> @!fa (filter #(and (= "value" (:t %)) (= "a2" (:id %)))) first :s))
            "channel a's *1 reflects only what a evaluated")
        (is (= "200" (->> @!fb (filter #(and (= "value" (:t %)) (= "b2" (:id %)))) first :s))
            "channel b's *1 reflects only what b evaluated")))))

(deftest closing-a-repl-channel-closes-its-session
  (with-repl-server
    (fn [s]
      (let [!f (atom [])
            ch (ws-connect (:port s) "/repl" !f)]
        (ws-send! ch (proto/encode {:t "eval" :id "c1" :code "1" :ns "user"}))
        (is (some? (wait-for !f #(= "done" (:t %)))))
        (is (= 1 (count @(:!repl-sessions s))) "the session was recorded")
        (ws-close! ch)
        (is (= 0
               (loop [n 0]
                 (let [c (count @(:!repl-sessions s))]
                   (if (or (zero? c) (>= n 300))
                     c
                     (do (Thread/sleep 10) (recur (inc n)))))))
            "the session entry is removed once the channel closes")))))

(deftest a-repl-channel-that-never-sends-a-frame-leaves-no-session
  (with-repl-server
    (fn [s]
      (let [!f (atom [])
            ch (ws-connect (:port s) "/repl" !f)]
        (ws-close! ch)
        (Thread/sleep 200)
        (is (= 0 (count @(:!repl-sessions s)))
            "no nREPL session was ever created for a channel that only connected")))))

;; A failed nREPL connect must not poison the session forever, and it must
;; not be able to prevent stop! from actually releasing the port.

(deftest a-repl-frame-against-an-unreachable-nrepl-gets-err-then-done
  (with-server-nrepl-port (unused-port)
    (fn [s]
      (let [!f (atom [])
            ch (ws-connect (:port s) "/repl" !f)]
        (ws-send! ch (proto/encode {:t "eval" :id "d1" :code "1" :ns "user"}))
        (is (some? (wait-for !f #(= "done" (:t %)))))
        (is (some #(= "err" (:t %)) @!f)
            "the browser is told the connect failed, instead of silence")
        (is (every? #(= "d1" (:id %)) @!f) "every frame carries the request id")
        (is (= 0 (count @(:!repl-sessions s)))
            "the failed attempt left no entry behind to poison the next one")))))

(deftest a-failed-connect-is-retried-not-cached
  (let [port (unused-port)]
    (with-server-nrepl-port port
      (fn [s]
        (let [!f (atom [])
              ch (ws-connect (:port s) "/repl" !f)]
          (ws-send! ch (proto/encode {:t "eval" :id "e1" :code "1" :ns "user"}))
          (is (some? (wait-for !f #(= "done" (:t %)))))
          (is (some #(= "err" (:t %)) @!f) "the first attempt fails: nothing is listening yet")
          (is (= 0 (count @(:!repl-sessions s))) "eviction left the map empty")
          (let [nrepl (nrepl-server/start-server :port port)]
            (try
              (ws-send! ch (proto/encode {:t "eval" :id "e2" :code "(+ 1 1)" :ns "user"}))
              (is (some? (wait-for !f #(and (= "done" (:t %)) (= "e2" (:id %))))))
              (is (= "2" (->> @!f (filter #(and (= "value" (:t %)) (= "e2" (:id %)))) first :s))
                  "the second attempt, made after the nREPL came up, actually connected -- the first failure was not cached and rethrown forever")
              (finally (nrepl-server/stop-server nrepl)))))))))

(deftest stop!-completes-even-with-a-poisoned-repl-session
  (tap/reset-all!)
  (pl/reset-all!)
  (let [dead-port (unused-port)
        s (server/start! {:port 0 :nrepl-port dead-port :root "client/dist"})]
    ;; Install a poisoned session directly -- realized?, and its deref
    ;; rethrows -- rather than relying on get-session!'s own eviction having
    ;; already cleaned it up, so this test proves stop! survives regardless.
    (let [d (delay (repl/connect! {:port dead-port}))]
      (try @d (catch Throwable _ nil))
      (swap! (:!repl-sessions s) assoc :poisoned d))
    (let [port (:port s)]
      (server/stop! s)
      (is (empty? @(:!repl-sessions s)) "the session map was cleared despite the poisoned entry")
      (is (empty? @(:!hud-clients s)) "the client set was cleared")
      (let [s2 (try (server/start! {:port port :nrepl-port nil :root "client/dist"})
                    (catch Exception e e))]
        (is (map? s2) "the HTTP port was actually released, so a fresh server could bind it")
        (when (map? s2) (server/stop! s2))))
    (tap/reset-all!)))
