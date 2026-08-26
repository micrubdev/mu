(ns mu.web.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.web.repl :as repl]
            [nrepl.server :as nrepl-server]))

(defn- with-nrepl
  "Start a real in-process nREPL server, run f with its port, stop it."
  [f]
  (let [server (nrepl-server/start-server :port 0)]
    (try (f (:port server))
         (finally (nrepl-server/stop-server server)))))

(defn- collect
  "Run an eval and return the frames it produced, waiting up to 5s."
  [session id code ns]
  (let [!frames (atom [])
        done    (promise)]
    (repl/eval! session id code ns
                (fn [f]
                  (swap! !frames conj f)
                  (when (= "done" (:t f)) (deliver done true))))
    (deref done 5000 :timeout)
    @!frames))

(deftest eval-returns-a-value-then-done
  (with-nrepl
    (fn [port]
      (let [s (repl/connect! {:port port})]
        (try
          (let [frames (collect s "u1" "(+ 1 1)" "user")]
            (is (= "2" (->> frames (filter #(= "value" (:t %))) first :s)))
            (is (= "done" (:t (last frames))))
            (is (every? #(= "u1" (:id %)) frames) "every frame carries the request id"))
          (finally (repl/close! s)))))))

(deftest stdout-arrives-as-out-frames
  (with-nrepl
    (fn [port]
      (let [s (repl/connect! {:port port})]
        (try
          (let [frames (collect s "u2" "(println \"hi\")" "user")]
            (is (= "hi\n" (->> frames (filter #(= "out" (:t %))) first :s))))
          (finally (repl/close! s)))))))

(deftest an-exception-arrives-as-an-ex-frame-and-still-finishes
  (with-nrepl
    (fn [port]
      (let [s (repl/connect! {:port port})]
        (try
          (let [frames (collect s "u3" "(/ 1 0)" "user")]
            (is (some #(= "ex" (:t %)) frames))
            (is (re-find #"Divide by zero"
                         (->> frames (filter #(= "err" (:t %))) (map :s) (apply str))))
            (is (= "done" (:t (last frames)))))
          (finally (repl/close! s)))))))

(deftest state-persists-across-evals-in-one-session
  (with-nrepl
    (fn [port]
      (let [s (repl/connect! {:port port})]
        (try
          (collect s "u4" "(def x 41)" "user")
          (is (= "42" (->> (collect s "u5" "(inc x)" "user")
                           (filter #(= "value" (:t %))) first :s)))
          (finally (repl/close! s)))))))

(deftest each-sessions-eval-history-is-its-own
  ;; nREPL isolates dynamic bindings per session -- *1, *2, *3, *ns*, and
  ;; the like -- so one tab's Emacs session doesn't stomp another tab's.
  ;; It does not, and must not, isolate namespaces (see
  ;; `a-def-in-one-session-is-visible-from-another` below).
  (with-nrepl
    (fn [port]
      (let [a (repl/connect! {:port port})
            b (repl/connect! {:port port})]
        (try
          (collect a "u6" "100" "user")
          (collect b "u7" "200" "user")
          (is (= "100" (->> (collect a "u8" "*1" "user")
                            (filter #(= "value" (:t %))) first :s))
              "session a's *1 reflects only what a evaluated")
          (is (= "200" (->> (collect b "u9" "*1" "user")
                            (filter #(= "value" (:t %))) first :s))
              "session b's *1 reflects only what b evaluated")
          (finally (repl/close! a) (repl/close! b)))))))

(deftest a-def-in-one-session-is-visible-from-another
  ;; This is the property the live-coding model depends on: a `(def
  ;; bass ...)` evaluated from one client (an editor buffer, another
  ;; browser tab) must be visible to every other client attached to the
  ;; same process, and to the render thread. Namespaces are global to
  ;; the JVM; only dynamic bindings are per-session. Pinned here so this
  ;; sharing doesn't get "fixed" away later.
  (with-nrepl
    (fn [port]
      (let [a (repl/connect! {:port port})
            b (repl/connect! {:port port})]
        (try
          (collect a "u10" "(def shared-across-sessions 99)" "user")
          (is (= "99" (->> (collect b "u11" "shared-across-sessions" "user")
                           (filter #(= "value" (:t %))) first :s))
              "the second session can see the first session's def")
          (finally (repl/close! a) (repl/close! b)))))))
