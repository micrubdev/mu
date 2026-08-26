(ns mu.web
  "The one call a performer makes: (web!).

  Kept out of src/ so that mu's core stays loadable with nothing but
  Clojure on the classpath. Run with the :web alias."
  (:require [mu.web.server :as server]))

(defonce ^:private !server (atom nil))

(defn web!
  "Start the web view and return its URL.

  :nrepl-port must be the port THIS process's nREPL is listening on --
  under `clojure -M:live` that is printed at startup, or read from
  .nrepl-port. Without it the editor pane is read-only and /repl returns
  503; the HUD still works."
  ([] (web! {}))
  ([{:keys [port nrepl-port root] :or {port 7890 root "client/dist"}}]
   (when-let [s @!server] (server/stop! s))
   (let [s (server/start! {:port port :nrepl-port nrepl-port :root root})]
     (reset! !server s)
     (str "http://localhost:" (:port s)))))

(defn web-off! []
  (when-let [s @!server] (server/stop! s))
  (reset! !server nil)
  :stopped)
