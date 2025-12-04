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

(ns milla.merge
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [milla.core :as core]
            [next.jdbc :as jdbc]
            [clojure.data.json :as json]))

(defn- ensure-schema!
  "Ensure dest DB has the required schema."
  [ds]
  (jdbc/execute! ds ["
    create table if not exists statements (
      id integer primary key,
      kind text not null,
      text text not null,
      created_at text not null,
      source_node text not null
    )"])
  (jdbc/execute! ds ["
    create table if not exists chat (
      id integer primary key,
      role text not null,
      model text,
      content text not null,
      session text not null default 'default',
      created_at text not null,
      responded_at text,
      source_node text not null
    )"])
  (jdbc/execute! ds ["
    create table if not exists chat_summaries (
      id integer primary key,
      session text not null,
      start_id integer not null,
      end_id integer not null,
      summary text not null,
      created_at text not null,
      source_node text not null
    )"]))

(defn- merge-table! [ds src-alias table sql-frag]
  (jdbc/execute! ds [(format "
    insert into %s
    %s" table sql-frag)
                     src-alias]))

(defn merge-dbs!
  "Merge db-a and db-b into dest (new file). Dedupes rows and re-runs summaries per session.
   Throws if dest matches either source."
  [dest db-a db-b]
  (let [dest-file (io/file dest)
        a-file    (io/file db-a)
        b-file    (io/file db-b)]
    (when (or (= (.getAbsolutePath dest-file) (.getAbsolutePath a-file))
              (= (.getAbsolutePath dest-file) (.getAbsolutePath b-file)))
      (throw (ex-info "Dest DB must differ from sources" {:milla/error :invalid-merge-path})))
    (when (.exists dest-file)
      (io/delete-file dest-file))
    (when-let [p (.getParentFile dest-file)]
      (.mkdirs p))
    (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getAbsolutePath dest-file)})]
      (ensure-schema! ds)
      ;; attach sources
      (jdbc/execute! ds [(format "attach database '%s' as db1" (.getAbsolutePath a-file))])
      (jdbc/execute! ds [(format "attach database '%s' as db2" (.getAbsolutePath b-file))])
      (try
        ;; statements from db1 then db2
        (doseq [src ["db1" "db2"]]
          (jdbc/execute! ds [(format "
            insert into statements(kind, text, created_at, source_node)
            select s.kind, s.text, s.created_at, s.source_node
            from %s.statements s
            where not exists (
              select 1 from statements t
              where t.kind = s.kind
                and t.text = s.text
                and t.created_at = s.created_at
                and t.source_node = s.source_node
            )" src)]))
        ;; chat
        (doseq [src ["db1" "db2"]]
          (jdbc/execute! ds [(format "
            insert into chat(role, model, content, session, created_at, responded_at, source_node)
            select c.role, c.model, c.content, c.session, c.created_at, c.responded_at, c.source_node
            from %s.chat c
            where not exists (
              select 1 from chat t
              where t.role = c.role
                and coalesce(t.model, '') = coalesce(c.model, '')
                and t.content = c.content
                and t.session = c.session
                and t.created_at = c.created_at
                and coalesce(t.responded_at, '') = coalesce(c.responded_at, '')
                and t.source_node = c.source_node
            )" src)]))
        ;; chat summaries
        (doseq [src ["db1" "db2"]]
          (jdbc/execute! ds [(format "
            insert into chat_summaries(session, start_id, end_id, summary, created_at, source_node)
            select s.session, s.start_id, s.end_id, s.summary, s.created_at, s.source_node
            from %s.chat_summaries s
            where not exists (
              select 1 from chat_summaries t
              where t.session = s.session
                and t.start_id = s.start_id
                and t.end_id = s.end_id
                and t.summary = s.summary
                and t.source_node = s.source_node
            )" src)]))
        (finally
          (jdbc/execute! ds ["detach database db1"])
          (jdbc/execute! ds ["detach database db2"])))
      ;; re-run summarization per session using the merged DB
      (let [sessions (map :session (jdbc/execute! ds ["select distinct session from chat"]))]
        (doseq [s sessions]
          (with-redefs [core/ds ds
                        core/db-spec {:dbtype "sqlite" :dbname (.getAbsolutePath dest-file)}]
            (core/ensure-summaries! s core/default-model))))
      ;; overall conversation summary across all sessions
      (let [rows (jdbc/execute! ds ["select role, content, session, created_at from chat order by created_at asc"])
            formatted (str/join
                       "\n"
                       (map (fn [{:keys [role content session created_at]}]
                              (format "- [%s] (%s) %s: %s" created_at session role content))
                            rows))
            prompt (str "Summarize the following entire conversation history into concise bullet points. "
                        "Capture key events, dates/times, tasks, and decisions. Avoid fluff.\n\n"
                        formatted)
            summary (core/ollama-generate {:model core/default-model
                                           :messages [{:role "user" :content prompt}]})]
        (jdbc/execute! ds ["create table if not exists global_summary (id integer primary key, summary text not null, created_at text not null, source_node text not null)"])
        (jdbc/execute! ds ["delete from global_summary"])
        (jdbc/execute! ds ["insert into global_summary(summary, created_at, source_node) values (?, ?, ?)"
                           summary (core/now) (core/current-node-id)]))
      dest)))

(defn -main [& args]
  (let [[dest a b] args]
    (when (or (str/blank? dest) (str/blank? a) (str/blank? b))
      (binding [*out* *err*]
        (println "Usage: clj -M -m milla.merge /path/to/dest.db /path/to/db1 /path/to/db2")
        (System/exit 1)))
    (try
      (merge-dbs! dest a b)
      (println "Merged into" dest)
      (catch Exception e
        (binding [*out* *err*]
          (println "Merge error:" (.getMessage e))
          (when-let [data (ex-data e)]
            (println "Details:" data)))
        (System/exit 1)))))
