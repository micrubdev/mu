(ns mu.web.server-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [mu.notation :refer [notes]]
            [mu.player :as pl]
            [mu.tap :as tap]
            [mu.web.protocol :as proto]
            [mu.web.server :as server]
            [org.httpkit.client :as http])
  (:import [java.net URI]
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
