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

(ns milla.sync
  (:require [clojure.string :as str]
            [milla.core :as core]
            [next.jdbc :as jdbc]))

(defn- ensure-schema!
  "Ensure local schema exists (mirrors milla.core/init!)."
  []
  (jdbc/execute! core/ds ["
    create table if not exists statements (
      id integer primary key,
      kind text not null,
      text text not null,
      created_at text not null,
      source_node text not null
    )"])
  (jdbc/execute! core/ds ["
    create table if not exists chat (
      id integer primary key,
      role text not null,
      model text,
      content text not null,
      session text not null default 'default',
      created_at text not null,
      source_node text not null
    )"])
  (jdbc/execute! core/ds ["
    create table if not exists chat_summaries (
      id integer primary key,
      session text not null,
      start_id integer not null,
      end_id integer not null,
      summary text not null,
      created_at text not null,
      source_node text not null
    )"])])

(defn- sql-quote [s]
  ;; Escape single quotes for use in SQL string literal contexts.
  (str/replace s "'" "''"))

(defn merge-remote!
  "Merge data from remote SQLite file into the configured local DB.
   Avoids duplicates based on content/time/source for each table."
  ([remote-path]
   (merge-remote! (get-in core/config [:db :path]) remote-path))
  ([local-path remote-path]
   (when-not (.exists (java.io.File. remote-path))
     (throw (ex-info "Remote DB not found" {:milla/error :missing-remote :path remote-path})))
   (when-not (.exists (java.io.File. local-path))
     ;; Ensure the local DB file exists and has schema.
     (core/init!))
   (ensure-schema!)
   (let [attach-sql (format "attach database '%s' as remote" (sql-quote remote-path))
         detach-sql "detach database remote"]
     (jdbc/execute! core/ds [attach-sql])
     (try
       (jdbc/with-transaction [tx core/ds]
         ;; Statements: dedupe on kind/text/created_at/source_node
         (jdbc/execute! tx ["
           insert into statements(kind, text, created_at, source_node)
           select r.kind, r.text, r.created_at, r.source_node
           from remote.statements r
           where not exists (
             select 1 from statements l
             where l.kind = r.kind
               and l.text = r.text
               and l.created_at = r.created_at
               and l.source_node = r.source_node
           )"])
         ;; Chat: dedupe on role/model/content/session/created_at/source_node
         (jdbc/execute! tx ["
           insert into chat(role, model, content, session, created_at, source_node)
           select r.role, r.model, r.content, r.session, r.created_at, r.source_node
           from remote.chat r
           where not exists (
             select 1 from chat l
             where l.role = r.role
               and coalesce(l.model, '') = coalesce(r.model, '')
               and l.content = r.content
               and l.session = r.session
               and l.created_at = r.created_at
               and l.source_node = r.source_node
           )"])
         ;; Chat summaries: dedupe on session/start/end/summary/source_node
         (jdbc/execute! tx ["
           insert into chat_summaries(session, start_id, end_id, summary, created_at, source_node)
           select r.session, r.start_id, r.end_id, r.summary, r.created_at, r.source_node
           from remote.chat_summaries r
           where not exists (
             select 1 from chat_summaries l
             where l.session = r.session
               and l.start_id = r.start_id
               and l.end_id = r.end_id
               and l.summary = r.summary
               and l.source_node = r.source_node
           )"])])
       (finally
         (jdbc/execute! core/ds [detach-sql]))))
   :ok))

(defn -main
  "Usage: clj -M -m milla.sync merge /path/to/remote.db"
  [& args]
  (let [[cmd remote] args]
    (try
      (cond
        (not= cmd "merge")
        (do (println "Usage: clj -M -m milla.sync merge /path/to/remote.db") (System/exit 1))

        (str/blank? remote)
        (do (println "Remote DB path is required") (System/exit 1))

        :else
        (do
          (merge-remote! remote)
          (println "Merge complete from" remote))))
      (catch Exception e
        (binding [*out* *err*]
          (println "Sync error:" (.getMessage e))
          (when-let [data (ex-data e)]
            (println "Details:" data)))
        (System/exit 1)))))
