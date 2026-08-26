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

(deftest two-sessions-do-not-share-state
  (with-nrepl
    (fn [port]
      (let [a (repl/connect! {:port port})
            b (repl/connect! {:port port})]
        (try
          (collect a "u6" "(def only-in-a 1)" "user")
          (let [frames (collect b "u7" "only-in-a" "user")]
            (is (some #(= "ex" (:t %)) frames)
                "the second session cannot see the first session's def"))
          (finally (repl/close! a) (repl/close! b)))))))
