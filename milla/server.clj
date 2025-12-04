;; Copyright (C) 2025 Dan Anderson
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as
;; published by the Free Software Foundation, either version 3 of the
;; License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.

(ns milla.server
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [milla.core :as core]
   [milla.self-edit :as self-edit]
   [org.httpkit.server :as http])
  (:import (java.lang.management ManagementFactory)))

(def default-port 17863)
(def default-heartbeat-ms 5000)
(def default-heartbeat-stale-ms 15000)

(defn- now
  "Millis timestamp for heartbeat bookkeeping."
  []
  (System/currentTimeMillis))

(defn- pid
  "Process pid from JVM runtime."
  []
  (let [runtime (ManagementFactory/getRuntimeMXBean)
        name (.getName runtime)]
    (first (str/split name #"\@"))))

(defn pid-file
  "Heartbeat/pid file location, expanded from config."
  []
  (let [p (or (get-in core/config [:server :pid_file])
              "milla.pid")]
    (io/file (core/expand-path p))))

(defn heartbeat!
  "Write current pid/port/host heartbeat."
  []
  (let [f (pid-file)
        data {:pid (pid)
              :port (or (get-in core/config [:server :port]) default-port)
              :host (or (get-in core/config [:server :host]) "127.0.0.1")
              :updated_at (now)}]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f (json/write-str data))))

(defn- stale? [hb max-ms]
  (let [t (:updated_at hb)]
    (or (nil? t) (> (- (now) t) max-ms))))

(defn read-heartbeat
  "Read heartbeat JSON from pid file."
  []
  (let [f (pid-file)]
    (when (.exists f)
      (try
        (json/read-str (slurp f) :key-fn keyword)
        (catch Exception _ nil)))))

(defn stale-heartbeat?
  "True if heartbeat is missing/older than default stale window."
  [hb]
  (stale? hb default-heartbeat-stale-ms))

(defn- wrap-json [handler]
  (fn [req]
    (handler req)))

(defn- ask-handler
  "HTTP handler for /ask: forwards prompt to core/ask!."
  [req]
  (let [body (slurp (:body req))
        {:keys [prompt model session]} (json/read-str body :key-fn keyword)]
    (try
      (let [answer (core/ask! {:prompt prompt
                               :model model
                               :session session})]
        {:status 200
         :headers {"content-type" "application/json"}
         :body (json/write-str {:answer answer})})
      (catch Exception e
        {:status 500
         :headers {"content-type" "application/json"}
         :body (json/write-str {:error (.getMessage e)
                                :data (ex-data e)})}))))

(defn routes [req]
  (case (:uri req)
    "/health" {:status 200 :headers {"content-type" "application/json"} :body (json/write-str {:ok true})}
    "/admin" {:status 200 :headers {"content-type" "application/json"}
              :body (json/write-str {:config-path (core/config-path)
                                     :db (core/db-path)
                                     :pid (pid)
                                     :port (or (get-in core/config [:server :port]) default-port)})}
    "/code/list" (if (self-edit/enabled?)
                   {:status 200
                    :headers {"content-type" "application/json"}
                    :body (json/write-str {:files (self-edit/list-files)})}
                   {:status 403 :body "self-edit disabled"})
    "/code/read" (if (self-edit/enabled?)
                   (try
                     (let [{:keys [path]} (json/read-str (slurp (:body req)) :key-fn keyword)]
                       {:status 200
                        :headers {"content-type" "application/json"}
                        :body (json/write-str (self-edit/read-file path))})
                     (catch Exception e
                       {:status 400
                        :headers {"content-type" "application/json"}
                        :body (json/write-str {:error (.getMessage e)
                                               :data (ex-data e)})}))
                   {:status 403 :body "self-edit disabled"})
    "/code/patch" (if (self-edit/enabled?)
                    (try
                      (let [body (json/read-str (slurp (:body req)) :key-fn keyword)
                            result (self-edit/apply-replace! body)]
                        {:status 200
                         :headers {"content-type" "application/json"}
                         :body (json/write-str (assoc result :message "patched"))})
                      (catch Exception e
                        {:status 400
                         :headers {"content-type" "application/json"}
                         :body (json/write-str {:error (.getMessage e)
                                                :data (ex-data e)})}))
                    {:status 403 :body "self-edit disabled"})
    "/ask" (ask-handler req)
    {:status 404 :body "not found"}))

(defonce server* (atom nil))
(defonce heartbeat-thread* (atom nil))

(defn start!
  ([] (start! (or (get-in core/config [:server :port]) default-port)))
  ([port]
   (core/init!)
   (println (str "Using config " (core/config-path) " db " (core/db-path)))
   (core/log-info (str "Using config " (core/config-path) " db " (core/db-path)))
   (let [hb (read-heartbeat)]
     (when (and hb (not (stale-heartbeat? hb)))
       (throw (ex-info "Milla server already running" {:milla/error :server-running :heartbeat hb}))))
   (when-not @server*
     (reset! server*
             (http/run-server routes {:port port
                                      :ip (or (get-in core/config [:server :host])
                                              (when-let [sock (get-in core/config [:server :unix_socket])]
                                                sock)
                                              "0.0.0.0")}))
     (core/log-info (str "Milla server started on port " port
                         " (node " core/default-node-id
                         " @ " core/default-node-location ")"))
     (heartbeat!)
     (reset! heartbeat-thread*
             (future
               (while @server*
                 (heartbeat!)
                 (Thread/sleep (or (get-in core/config [:server :heartbeat_ms]) default-heartbeat-ms))))))))

(defn stop! []
  (when @server*
    (@server*)
    (reset! server* nil)
    (when @heartbeat-thread*
      (future-cancel @heartbeat-thread*)
      (reset! heartbeat-thread* nil))
    (core/log-info "Milla server stopped")))

(defn -main [& _]
  (start!))
