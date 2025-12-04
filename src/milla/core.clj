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

(ns milla.core
  (:require
   [clj-http.client :as http]
   [clj-yaml.core :as yaml]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [next.jdbc.sql :as sql])
  (:import
   (java.time Instant))
  (:gen-class))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-config
  {:db    {:path "milla_memory.db"}
   :node  {:id "home-milla"
           :location "unknown"}
   :log   {:level "info"
           :file "milla.log"}
   :ollama {:url "http://localhost:11434/api/chat"   ;; Option B: chat endpoint
            :default_model "llama3.2"
            :keep_alive "10m"
            :embedding_model "nomic-embed-text"}
   :chat  {:default_session "default"
           :history_limit 50}
   :prompt {:max_tokens 2000}
   :thermal {:enabled false
             :max_c 85
             :cooldown_ms 120000
             :sensor_path "/sys/class/thermal/thermal_zone0/temp"}
   :self_edit {:enabled false
               :intent "rag-tools"          ;; only allow edits for adding/extending RAG tools
               :allow_paths ["src/milla/rag" "doc"] ;; constrained surface
               :require_user_confirm true}   ;; must be explicitly requested in API payload
   :rag   {:enabled false
           :top_k 5
           :min_score 0.2}})

(defn- env-value [k]
  (let [v (System/getenv k)]
    (when (and v (not (str/blank? v))) v)))

(defn- env-int [k]
  (when-let [v (env-value k)]
    (try
      (Integer/parseInt v)
      (catch Exception _ nil))))
(defn- env-str [k] (env-value k))

(def default-config-paths
  ["config/milla.yaml"
   "milla-config.yaml"
   "milla.yaml"])

(defn config-path []
  (or (env-value "MILLA_CONFIG")
      (some (fn [path]
              (let [f (io/file path)]
                (when (.exists f) path)))
            default-config-paths)
      (first default-config-paths)))

(defn- deep-merge
  "Recursively merge maps. Later values win."
  ([a b]
   (merge-with (fn [x y]
                 (if (and (map? x) (map? y))
                   (deep-merge x y)
                   y))
               a b))
  ([a b & more]
   (reduce deep-merge (deep-merge a b) more)))

(defn- read-config-file []
  (let [file (io/file (config-path))]
    (when (.exists file)
      (yaml/parse-string (slurp file) :keywords true))))

(defn expand-path
  "Expand a path, handling ~ for user home."
  [p]
  (let [p (str p)]
    (cond
      (str/starts-with? p "~/")
      (let [home (System/getProperty "user.home")]
        (str home (subs p 1)))

      :else p)))

(def config
  (let [file-config    (or (read-config-file) {})
        merged         (deep-merge default-config file-config)
        db-path-env    (env-value "MILLA_DB_PATH")
        node-id-env    (env-value "MILLA_NODE_ID")
        node-loc-env   (env-value "MILLA_NODE_LOCATION")
        ollama-url-env (env-value "OLLAMA_URL")
        ollama-model   (env-value "OLLAMA_MODEL")
        default-sess   (env-value "MILLA_DEFAULT_SESSION")
        keep-alive-env (env-str "OLLAMA_KEEP_ALIVE")
        hist-limit-env (env-int "CHAT_HISTORY_LIMIT")
        max-tokens-env (env-int "MAX_PROMPT_TOKENS")]
    (cond-> merged
      db-path-env    (assoc-in [:db :path] db-path-env)
      node-id-env    (assoc-in [:node :id] node-id-env)
      node-loc-env   (assoc-in [:node :location] node-loc-env)
      ollama-url-env (assoc-in [:ollama :url] ollama-url-env)
      ollama-model   (assoc-in [:ollama :default_model] ollama-model)
      keep-alive-env (assoc-in [:ollama :keep_alive] keep-alive-env)
      hist-limit-env (assoc-in [:chat :history_limit] hist-limit-env)
      max-tokens-env (assoc-in [:prompt :max_tokens] max-tokens-env)
      default-sess   (assoc-in [:chat :default_session] default-sess))))

(def default-node-id (get-in config [:node :id]))
(def default-node-location (get-in config [:node :location] "unknown"))
(def default-model   (get-in config [:ollama :default_model]))
(def default-session (get-in config [:chat :default_session] "default"))
(def thermal-config (get-in config [:thermal]))
(defn- canonicalize-ollama-url [u]
  (let [s (str (or u ""))]
    (cond
      (str/blank? s) s
      (str/ends-with? s "/api/chat") s
      (str/ends-with? s "/api/generate") (str (subs s 0 (- (count s) (count "/api/generate"))) "/api/chat")
      (str/ends-with? s "/api") (str s "/chat")
      (str/ends-with? s "/") (str s "api/chat")
      :else (str s "/api/chat"))))

(def ollama-url      (canonicalize-ollama-url (get-in config [:ollama :url])))
(def ollama-keep-alive (or (get-in config [:ollama :keep_alive]) "10m"))
;; Ollama may return {:done_reason "load"} while the model is warming up.
;; Allow configurable, generous retry window (defaults: 900 attempts, 2s backoff ≈30m).
(def ollama-retries (or (env-int "OLLAMA_RETRIES") 900))
(def ollama-retry-sleep-ms (or (env-int "OLLAMA_RETRY_SLEEP_MS") 2000))
;; Chat history + summarization (configurable)
(def history-limit (or (get-in config [:chat :history_limit]) 50))
(def send-recent-limit 5)
(def summary-chunk-size history-limit)
(def summary-trigger-count (long (Math/ceil (* 1.5 history-limit))))
;; Prompt token budget (passed as num_ctx)
(def max-prompt-tokens (or (get-in config [:prompt :max_tokens]) 2000))
(def rag-enabled (true? (get-in config [:rag :enabled])))
(def rag-top-k (or (get-in config [:rag :top_k]) 5))
(def rag-min-score (or (get-in config [:rag :min_score]) 0.2))
(def embedding-model (get-in config [:ollama :embedding_model]))
(declare store-embedding!)
(declare chat-messages-by-session)

;; -----------------------------------------------------------------------------
;; Validation
;; -----------------------------------------------------------------------------

(defn validate-config
  "Ensure required config fields are present and sane."
  [cfg]
  (let [db-path (get-in cfg [:db :path])
        node-id (get-in cfg [:node :id])
        node-loc (get-in cfg [:node :location])
        url     (get-in cfg [:ollama :url])
        model   (get-in cfg [:ollama :default_model])
        keep-alive (get-in cfg [:ollama :keep_alive])
        hist-limit (get-in cfg [:chat :history_limit])
        max-tokens (get-in cfg [:prompt :max_tokens])
        thermal (:thermal cfg)
        server  (:server cfg)
        rag     (:rag cfg)
        embedding (get-in cfg [:ollama :embedding_model])]
    (cond
      (str/blank? db-path)
      (throw (ex-info "Config missing db.path" {:milla/error :invalid-config}))

      (str/blank? node-id)
      (throw (ex-info "Config missing node.id" {:milla/error :invalid-config}))

      (str/blank? node-loc)
      (throw (ex-info "Config missing node.location" {:milla/error :invalid-config}))

      (str/blank? url)
      (throw (ex-info "Config missing ollama.url" {:milla/error :invalid-config}))

      (str/blank? model)
      (throw (ex-info "Config missing ollama.default_model" {:milla/error :invalid-config}))

      (and keep-alive (str/blank? keep-alive))
      (throw (ex-info "Config keep_alive must be non-blank when set" {:milla/error :invalid-config}))

      (or (nil? hist-limit) (neg? hist-limit) (zero? hist-limit))
      (throw (ex-info "Config chat.history_limit must be positive" {:milla/error :invalid-config}))

      (or (nil? max-tokens) (<= max-tokens 0))
      (throw (ex-info "Config prompt.max_tokens must be positive" {:milla/error :invalid-config}))

      (and (:enabled thermal)
           (or (not (number? (:max_c thermal)))
               (<= (:max_c thermal) 0)))
      (throw (ex-info "Config thermal.max_c must be positive when enabled" {:milla/error :invalid-config}))

      (and server (or (nil? (:port server)) (<= (:port server) 0)))
      (throw (ex-info "Config server.port must be positive" {:milla/error :invalid-config}))

      (and (:enabled rag)
           (or (nil? embedding) (str/blank? embedding)))
      (throw (ex-info "Config ollama.embedding_model required when RAG enabled" {:milla/error :invalid-config}))

      (and (:enabled rag)
           (or (nil? (get rag :top_k)) (<= (get rag :top_k) 0)))
      (throw (ex-info "Config rag.top_k must be positive when RAG enabled" {:milla/error :invalid-config}))

      :else
      :ok)))

(defn ensure-config! [] (validate-config config))

;; =============================================================================
;; Storage (SQLite via next.jdbc)
;; =============================================================================

(def db-spec
  (let [path (-> (get-in config [:db :path])
                 expand-path
                 io/file
                 .getAbsolutePath)]
    {:dbtype "sqlite"
     :dbname path}))

(defn db-path []
  (:dbname db-spec))

(def ds
  ;; Use unqualified, lower-case keys from queries.
  (jdbc/with-options (jdbc/get-datasource db-spec)
    {:builder-fn rs/as-unqualified-lower-maps}))

(defonce ^:private node-id* (atom default-node-id))
(defn current-node-id [] @node-id*)

(defn set-node-id!
  "Override the node identifier used when persisting state."
  [new-id]
  (reset! node-id* (if (str/blank? new-id) default-node-id new-id)))

(defn- now [] (.toString (Instant/now)))

;; -----------------------------------------------------------------------------
;; Logging
;; -----------------------------------------------------------------------------

(defn- log-level-value [lvl]
  (case (some-> lvl str/lower-case str/trim)
    "error" 3
    "warn"  2
    "info"  1
    nil))

(def log-level
  (let [cfg (get-in config [:log :level])]
    (or (log-level-value cfg) 1)))

(def log-file
  (some-> (get-in config [:log :file]) str/trim not-empty))

(defn- should-log? [lvl]
  (when-let [v (log-level-value lvl)]
    (>= v log-level)))

(defn- fmt-log-line [lvl msg]
  (str (now) " [" (str/upper-case (name lvl)) "] " msg))

(def ^:const max-log-bytes (* 5 1024 1024)) ;; 5MB cap
(def log-request-bodies? (not= "false" (str/lower-case (str (get-in config [:log :request_bodies?] "true")))))

(defn- append-log!
  "Write a single log line to the configured file with level and timestamp, truncating when oversized."
  [lvl msg]
  (when (and log-file (should-log? lvl))
    (let [line (fmt-log-line lvl msg)
          f    (io/file (expand-path log-file))]
      (when-let [p (.getParentFile f)]
        (.mkdirs p))
      (locking append-log!
        ;; basic size cap: truncate if file exceeds max-log-bytes
        (when (and (.exists f) (> (.length f) max-log-bytes))
          (spit f "")) ;; reset
        (try
          (spit f (str line "\n") :append true)
          (println (str "Opening log file " (.getAbsolutePath f) " ... ok"))
          (catch Exception e
            (println (str "Opening log file " (.getAbsolutePath f) " ... failed: " (.getMessage e))))))
      (when (= lvl :error)
        (binding [*out* *err*]
          (println line))))))

(defn log-info
  "Log an info message to file (and stderr if level is error)."
  [msg]
  (append-log! :info msg))
(defn log-warn
  "Log a warning message to file."
  [msg]
  (append-log! :warn msg))
(defn log-error
  "Log an error message to file and stderr."
  [msg]
  (append-log! :error msg))

;; -----------------------------------------------------------------------------
;; Thermal throttling (optional)
;; -----------------------------------------------------------------------------

(defn- read-thermal-c []
  (let [path (some-> thermal-config :sensor_path expand-path)]
    (when (and path (.exists (io/file path)))
      (try
        (let [raw (slurp path)
              n   (Long/parseLong (str/trim raw))]
          ;; Many sensors report millidegrees.
          (if (> n 1000) (/ n 1000.0) (double n)))
        (catch Exception _
          nil)))))

(defn maybe-cooldown! []
  (when (:enabled thermal-config)
    (if-let [temp (read-thermal-c)]
      (let [max-c (:max_c thermal-config 85)
            cooldown (:cooldown_ms thermal-config 120000)]
        (when (>= temp max-c)
          (log-warn (format "Thermal threshold hit (%.1fC >= %.1fC). Cooling for %.1f seconds."
                            temp max-c (/ cooldown 1000.0)))
          (Thread/sleep cooldown)))
      (log-warn "Thermal monitoring enabled but sensor_path not readable; skipping cooldown check."))))

;; -----------------------------------------------------------------------------
;; DDL helpers
;; -----------------------------------------------------------------------------

(defn- table-exists? [table]
  (pos? (count (jdbc/execute! ds
                              ["select name
                                from sqlite_master
                                where type='table'
                                  and name=?"
                               table]))))

(defn- column-exists?
  [table column]
  (let [column (str/lower-case column)]
    (some #(= column (-> % :name str/lower-case))
          (jdbc/execute! ds [(str "pragma table_info(" table ")")]))))

(defn- ensure-column!
  "If TABLE exists and COLUMN does not, add it with the given DDL fragment
   (e.g. `\"source_node text not null default 'unknown'\"`)."
  [table column ddl]
  (when (and (table-exists? table)
             (not (column-exists? table column)))
    (jdbc/execute! ds [(format "alter table %s add column %s" table ddl)])))

(defn init!
  "Ensure required tables exist and migrations are applied."
  []
  (ensure-config!)
  ;; main tables
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
    )"])
  (jdbc/execute! ds ["
    create table if not exists embeddings (
      id integer primary key,
      kind text not null,
      row_id integer not null,
      session text,
      content text not null,
      embedding text not null,
      created_at text not null,
      source_node text not null
    )"])
  ;; migrations / backfills
  (ensure-column! "statements" "source_node"
                  "source_node text not null default 'unknown'")
  (ensure-column! "chat" "session"
                  "session text not null default 'default'")
  (ensure-column! "chat" "source_node"
                  "source_node text not null default 'unknown'")
  (ensure-column! "chat" "responded_at"
                  "responded_at text")
  :ok)

(defn reset-db!
  "Delete the configured DB file (if present) and recreate an empty schema."
  []
  (let [path   (db-path)
        target (io/file path)]
    (when-let [parent (.getParentFile target)]
      (.mkdirs parent))
    (when (.exists target)
      (io/delete-file target))
    (init!)
    path))

;; =============================================================================
;; Statements API (facts, desires, opinions, backlog)
;; =============================================================================

(defn- persist-statement!
  "Insert a single statement row and cache its embedding."
  [kind text]
  (let [row (sql/insert! ds :statements
                         {:kind        kind
                          :text        text
                          :created_at  (now)
                          :source_node (current-node-id)})]
    (store-embedding! "statement" (:id row) nil text)
    row))

(defn- store-statements!
  "Store a collection of statements of a given kind."
  [kind statements]
  (init!)
  (doseq [s (->> statements
                 (map str)
                 (map str/trim)
                 (remove str/blank?))]
    (persist-statement! kind s))
  :ok)

(defn fact
  "Store one or more factual statements about the world/user."
  [& ss]
  (store-statements! "fact" ss))

(defn desire
  "Store one or more desires/goals/preferences."
  [& ss]
  (store-statements! "desire" ss))

(defn opinion
  "Store one or more opinions."
  [& ss]
  (store-statements! "opinion" ss))

(defn backlog
  "Store backlog items for later review."
  [& ss]
  (store-statements! "backlog" ss))

(defn- statements-by-kind [kind]
  (init!)
  (sql/query ds
             ["select id, kind, text, created_at, source_node
               from statements
               where kind = ?
               order by created_at asc"
              kind]))

(defn all-statements []
  (init!)
  (sql/query ds
             ["select id, kind, text, created_at, source_node
               from statements
               order by created_at asc"]))

(defn facts
  "Fetch all fact statements (oldest→newest)."
  []
  (statements-by-kind "fact"))
(defn desires
  "Fetch all desire statements (oldest→newest)."
  []
  (statements-by-kind "desire"))
(defn opinions
  "Fetch all opinion statements (oldest→newest)."
  []
  (statements-by-kind "opinion"))
(defn backlog-items
  "Fetch all backlog statements (oldest→newest)."
  []
  (statements-by-kind "backlog"))

;; =============================================================================
;; Chat summaries
;; =============================================================================

(defn chat-summaries
  "Return stored chat summaries (oldest → newest) for a session."
  [session]
  (init!)
  (sql/query ds
             ["select id, session, start_id, end_id, summary, created_at, source_node
               from chat_summaries
               where session = ?
               order by start_id asc"
              session]))

(defn- last-summary-end [session]
  (or (-> (sql/query ds
                     ["select max(end_id) as end_id from chat_summaries where session = ?" session])
          first
          :end_id)
      0))

(declare ollama-generate)
(defn- summarize-chunk!
  "Summarize a chunk of chat messages and persist the summary."
  [session model messages]
  (let [start-id (:id (first messages))
        end-id   (:id (last messages))
        formatted (str/join
                   "\n"
                   (map (fn [{:keys [role content created_at]}]
                          (str "- [" created_at "] " role ": " content))
                        messages))
        prompt    (str "Summarize the following chat messages in concise bullet points. "
                       "Capture key requests, answers, decisions, dates/times, and assignments. "
                       "Avoid fluff.\n\n"
                       formatted)
        summary   (ollama-generate {:model model
                                    :messages [{:role "user" :content prompt}]})]
    (sql/insert! ds :chat_summaries
                 {:session    session
                  :start_id   start-id
                  :end_id     end-id
                  :summary    summary
                  :created_at (now)
                  :source_node (current-node-id)})
    (when embedding-model
      (store-embedding! "chat_summary" nil session summary))
    (log-info (format "Summarized chat %s (%d-%d)" session start-id end-id))
    summary))

(defn ensure-summaries!
  "Maintain a rolling summary for all but the most recent `send-recent-limit` messages."
  [session model]
  (init!)
  (let [msgs (chat-messages-by-session session)
        cnt  (count msgs)]
    (when (> cnt send-recent-limit)
      (let [to-summarize (vec (take (- cnt send-recent-limit) msgs))
            _ (sql/delete! ds :chat_summaries {:session session})]
        (summarize-chunk! session model to-summarize)))
    :ok))

;; =============================================================================
;; Embeddings + RAG
;; =============================================================================

(defn- embedding-url []
  (let [base (get-in config [:ollama :url])]
    (cond
      (str/ends-with? base "/api/chat") (str (subs base 0 (- (count base) (count "/chat"))) "/embeddings")
      (str/ends-with? base "/api/generate") (str (subs base 0 (- (count base) (count "/generate"))) "/embeddings")
      :else (str base "/embeddings"))))

(defn ollama-embed
  "Call Ollama embeddings endpoint with the configured embedding model."
  [text]
  (let [model embedding-model
        _ (when (str/blank? model)
            (throw (ex-info "Embedding model not configured" {:milla/error :missing-embedding-model})))
        body {:model model
              :prompt text}
        resp (http/post (embedding-url)
                        {:content-type     :json
                         :accept           :json
                         :as               :text
                         :throw-exceptions false
                         :body             (json/write-str body)})
        status (:status resp)
        body-str (:body resp)]
    (if (<= 200 status 299)
      (let [data (json/read-str body-str :key-fn keyword)
            embedding (get-in data [:embedding])]
        (if embedding
          embedding
          (throw (ex-info "Embedding response missing embedding" {:milla/error :embedding-malformed :body body-str}))))
      (throw (ex-info "Embedding request failed" {:milla/error :embedding-http-error
                                                  :status status
                                                  :body body-str})))))

(defn embedding-exists? [kind row-id session]
  (pos? (count (sql/query ds ["select 1 from embeddings where kind=? and row_id=? and coalesce(session,'')=coalesce(?, '') limit 1" kind row-id session]))))

(defn store-embedding!
  "Persist embedding for content."
  [kind row-id session content]
  (when (and embedding-model rag-enabled)
    (try
      (when-not (embedding-exists? kind row-id session)
        (let [emb (ollama-embed content)]
          (sql/insert! ds :embeddings
                       {:kind kind
                        :row_id (or row-id 0)
                        :session session
                        :content content
                        :embedding (json/write-str emb)
                        :created_at (now)
                        :source_node (current-node-id)})))
      (catch Exception e
        (log-warn (str "Embedding failed: " (.getMessage e)))))))

(defn- cosine [a b]
  (let [dot (reduce + (map * a b))
        na (Math/sqrt (reduce + (map #(* % %) a)))
        nb (Math/sqrt (reduce + (map #(* % %) b)))]
    (if (or (zero? na) (zero? nb)) 0.0 (/ dot (* na nb)))))

(defn rag-context
  "Return top-k relevant entries as formatted strings."
  [query]
  (if (or (not rag-enabled) (str/blank? query))
    []
    (try
      (let [q-emb (ollama-embed query)
            entries (sql/query ds ["select kind, row_id, session, content, embedding from embeddings"])
            scored (->> entries
                        (keep (fn [{:keys [kind row_id session content embedding]}]
                                (when embedding
                                  (let [vec (json/read-str embedding)]
                                    {:score (cosine q-emb vec)
                                     :kind kind
                                     :row_id row_id
                                     :session session
                                     :content content}))))
                        (filter #(>= (:score %) rag-min-score))
                        (sort-by :score >)
                        (take rag-top-k))]
        (map (fn [{:keys [kind row_id session content score]}]
               (format "- [%s/%s %s score=%.3f] %s"
                       kind row_id (or session "global") score content))
             scored))
      (catch Exception e
        (log-warn (str "RAG retrieval failed: " (.getMessage e)))
        []))))

;; =============================================================================
;; Chat storage + history
;; =============================================================================

(defn- add-chat!
  "Persist a single chat message (user or assistant)."
  [role model content session]
  (sql/insert! ds :chat
               {:role        role
                :model       model
                :content     content
                :session     (or session default-session)
                :created_at  (now)
                :source_node (current-node-id)}))

(defn- update-response-timestamp!
  "Set responded_at for a chat row (assistant messages)."
  [id]
  (sql/update! ds :chat {:responded_at (now)} {:id id}))

(defn chat-messages-by-session
  "Return all chat messages for a session (oldest → newest)."
  [session]
  (init!)
  (sql/query ds
             ["select id, role, model, content, session, created_at, source_node
               from chat
               where session = ?
               order by created_at asc"
              session]))

(def ^:const history-limit 50)
(def ^:const summary-chunk-size 25)

(defn recent-chat
  "Return the last N chat messages for a session (oldest → newest)."
  ([session]
   (recent-chat session history-limit))
  ([session n]
   (let [all (chat-messages-by-session session)]
     (->> all (take-last n) vec))))

;; =============================================================================
;; System prompt + message construction
;; =============================================================================

(defn- format-history
  "Produce a human-readable summary of recent chat for the system prompt."
  [session]
  (let [entries (recent-chat session history-limit)]
    (if (seq entries)
      (str "CONVERSATION HISTORY (newest last):\n"
           (str/join
            "\n"
            (map (fn [{:keys [role content created_at]}]
                   (str "- [" created_at "] " role ": " content))
                 entries)))
      "CONVERSATION HISTORY: (none yet)")))

(defn build-system-prompt
  "Construct a system prompt summarizing persistent memory and recent chat."
  ([]
   (build-system-prompt default-session))
  ([session]
   (let [fmt-block (fn [title entries]
                     (str title ":\n"
                          (if (seq entries)
                            (str/join
                             "\n"
                             (map (fn [{:keys [text source_node]}]
                                    (str "- " text " (" source_node ")"))
                                  entries))
                            "- None recorded.")))
         sections  [(fmt-block "NODE" [{:text (str default-node-id " @ " default-node-location)
                                        :source_node default-node-id}])
                    (fmt-block "FACTS"   (facts))
                    (fmt-block "DESIRES" (desires))
                    (fmt-block "OPINIONS" (opinions))
                    (fmt-block "BACKLOG (context only)" (backlog-items))
                    (fmt-block "CONVERSATION SUMMARIES"
                               (chat-summaries session))
                    (format-history session)]]
     (str
      "You are Milla, the user's local assistant running on the user's own hardware.\n"
      "Use the conversation history to stay consistent.\n"
      "Do not claim you forgot something if it clearly appears in the history.\n\n"
      (str/join "\n\n" sections)
      "\n"))))

(defn- estimate-tokens [s]
  ;; crude heuristic: 4 chars ~ 1 token
  (int (Math/ceil (/ (count (str s)) 4.0))))

(defn- estimate-message-tokens [messages]
  (reduce + (map #(estimate-tokens (:content %)) messages)))

(defn- fit-messages
  "Trim message list from the oldest until total tokens <= max-tokens.
   Keeps the newest messages."
  [messages max-tokens]
  (loop [msgs (vec messages)]
    (let [tok (estimate-message-tokens msgs)]
      (if (<= tok max-tokens)
        msgs
        (recur (vec (rest msgs)))))))

(defn- session->messages
  "Turn recent DB chat history + the new user prompt into an Ollama `messages` vector
   suitable for /api/chat. Oldest → newest. Uses only the last `send-recent-limit`
   messages, with the rest summarized separately."
  [session new-user-content]
  (let [history (take-last send-recent-limit (chat-messages-by-session session))
        past    (mapv (fn [{:keys [role content]}]
                        {:role    role
                         :content content})
                      history)
        rag     (rag-context new-user-content)
        rag-msg (when (seq rag)
                  {:role "system" :content (str "RAG CONTEXT:\n" (str/join "\n" rag))})
        base    (cond-> past
                 rag-msg (conj rag-msg))
        all     (conj base {:role "user" :content new-user-content})]
    (fit-messages all max-prompt-tokens)))

;; =============================================================================
;; Ollama chat client (/api/chat)
;; =============================================================================

(defn- retry*
  "Call `f` up to `attempts` times with `sleep-ms` between failures.
   Rethrows the last exception if all attempts fail."
  [attempts f sleep-ms]
  (loop [n attempts]
    (let [result (try
                   {:ok (f)}
                   (catch Exception e
                     {:err e}))]
      (if-let [v (:ok result)]
        v
        (let [e (:err result)]
          (if (> n 1)
            (do
              (when (and sleep-ms (pos? sleep-ms))
                (Thread/sleep sleep-ms))
              (recur (dec n)))
            (throw e)))))))

(defn ollama-generate
  "Low-level Ollama call using /api/chat.

   Expected config: ollama.url points to http://host:11434/api/chat.

   Options map:
   - :model    (string)  : model name (defaults to config)
   - :system   (string)  : system prompt (optional)
   - :messages (vector)  : chat messages {:role \"user\"|\"assistant\"|\"system\" :content string}"
  [{:keys [model system messages]}]
  (let [model     (or model default-model)
        base-msgs (or (not-empty messages)
                      (throw (ex-info "ollama-generate requires :messages"
                                      {:milla/error :invalid-ollama-request})))
        full-msgs (if (seq system)
                    (into [{:role "system" :content system}] base-msgs)
                    base-msgs)
        body      {:model    model
                   :messages full-msgs
                   :stream   false
                   :keep_alive ollama-keep-alive
                   :options {:num_ctx max-prompt-tokens}}]
    (when log-request-bodies?
      (log-info (str "Ollama request: " (json/write-str body))))
    (retry* ollama-retries
            (fn []
              (let [resp     (http/post ollama-url
                                        {:content-type     :json
                                         :accept           :json
                                         :as               :text
                                         :throw-exceptions false
                                         :body             (json/write-str body)})
                    status   (:status resp)
                    body-str (:body resp)]
                (if (<= 200 status 299)
                  (let [data   (json/read-str body-str :key-fn keyword)
                        ;; Normal chat + fallback for /api/generate
                        answer (or (get-in data [:message :content])
                                   (:response data))
                        err    (:error data)
                        done   (some-> (:done_reason data) str/lower-case)]
                    (cond
                      (and answer (not (str/blank? answer)))
                      answer

                      err
                      (throw (ex-info (str "Ollama error: " err)
                                      {:milla/error :ollama-error
                                       :status      status
                                       :body        body-str
                                       :error       err}))

                      (= "load" done)
                      (throw (ex-info "Ollama model still loading; retrying"
                                      {:milla/error :ollama-model-loading
                                       :status      status
                                       :body        body-str
                                       :done_reason done}))

                      :else
                      (throw (ex-info "Ollama chat response missing content"
                                      {:milla/error :ollama-malformed-response
                                       :status      status
                                       :body        body-str
                                       :data        data}))))
                  (throw (ex-info "Ollama chat request failed"
                                  {:milla/error :ollama-http-error
                                   :status      status
                                   :body        body-str})))))
            ollama-retry-sleep-ms)))

;; =============================================================================
;; High-level ask! API
;; =============================================================================

(defn ask!
  "High-level chat entry point.

   Options:
   - :model   (string) model name (defaults to config)
   - :prompt  (string) user prompt (required, non-blank)
   - :session (string) session id (defaults to configured default)
   - :system  (string) override system prompt (optional; normally auto-built)"
  [{:keys [model prompt session system]}]
  (when (str/blank? prompt)
    (throw (ex-info "ask! requires non-blank :prompt"
                    {:milla/error :invalid-ask-request})))
  (init!)
  (let [session  (if (str/blank? session) default-session session)
        model    (or model default-model)]
    (maybe-cooldown!)
    ;; Persist user message first
    (let [{:keys [id]} (add-chat! "user" model prompt session)]
      (store-embedding! "chat_user" id session prompt))
    ;; Summarize older history before building prompt/context.
    (ensure-summaries! session model)
    (try
      (let [system   (or system (build-system-prompt session))
            messages (session->messages session prompt)
            answer   (ollama-generate {:model    model
                                       :system   system
                                       :messages messages})
            {:keys [id]} (add-chat! "assistant" model answer session)]
        (update-response-timestamp! id)
        (store-embedding! "chat_assistant" id session answer)
        answer)
      (catch Exception e
        (let [inner (ex-data e)]
          (throw (ex-info "ask! failed"
                          (merge {:milla/error :ask-failed
                                  :cause       (.getMessage e)}
                                 (when inner
                                   {:ollama inner}))
                          e)))))))

;; =============================================================================
;; CLI entrypoint
;; =============================================================================

(defn -main
  "CLI usage examples:

     # Use default model
     clj -M -m milla.core \"Hi, my name is Dan.\"

     # Explicit model
     clj -M -m milla.core llama3.2 \"Hi, my name is Dan.\"

   Session can be controlled by env (MILLA_DEFAULT_SESSION) or via future flags."
  [& args]
  (let [[model prompt] (cond
                         (empty? args)
                         [default-model "Hello from Milla!"]

                         (= 1 (count args))
                         [default-model (first args)]

                         :else
                         [(first args)
                          (str/join " " (rest args))])]
    (try
      (println (ask! {:model model
                      :prompt prompt}))
      (catch Exception e
        (binding [*out* *err*]
          (println "Error:" (.getMessage e))
          (when-let [data (ex-data e)]
            (println "Details:" data)))
        (System/exit 1)))))
(def ^:const log-request-bodies? (not= "false" (str/lower-case (or (get-in config [:log :request_bodies?]) "true"))))
