(ns mu.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [mu.web :as web]))

(deftest web-starts-and-reports-its-url
  (let [url (web/web! {:port 0 :nrepl-port nil})]
    (try
      (is (re-find #"^http://localhost:\d+$" url))
      (finally (web/web-off!)))))

(deftest starting-twice-replaces-the-first-server
  (let [a (web/web! {:port 0 :nrepl-port nil})
        b (web/web! {:port 0 :nrepl-port nil})]
    (try
      (is (not= a b) "the second call bound a new port")
      (finally (web/web-off!)))))

(deftest web-off-is-safe-when-nothing-is-running
  (is (= :stopped (web/web-off!)))
  (is (= :stopped (web/web-off!))))
