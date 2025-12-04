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

(ns milla.core-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [clj-http.client :as http]
            [milla.core :as core]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn with-temp-ds
  "Run f with a temp SQLite file bound to milla.core/ds (persists across connections)."
  [f]
  (let [tmp   (doto (java.io.File/createTempFile "milla-test" ".db")
                (.deleteOnExit))
        path  (.getAbsolutePath tmp)
        ds    (jdbc/with-options
                (jdbc/get-datasource {:dbtype "sqlite" :dbname path})
                {:builder-fn rs/as-unqualified-lower-maps})]
    (with-redefs [core/db-spec {:dbtype "sqlite" :dbname path}
                  core/ds      ds]
      (core/init!)
      (f ds))))

(deftest init-and-fact-persists
  (with-temp-ds
    (fn [_ds]
      (testing "facts are persisted with kind=fact"
        (core/fact "Dan likes Emacs" "Clojure is fun")
        (is (= 2 (count (core/facts))) "Expected two facts to be stored")
        (is (= #{"fact"} (set (map :kind (core/facts)))))))))

(deftest ask-persists-chat-history
  (with-temp-ds
    (fn [ds]
      (with-redefs [core/ollama-generate (fn [_] "stub-reply")]
        (core/ask! {:model "stub" :prompt "Hello" :session "test" :system ""})
        (let [rows (jdbc/execute! ds ["select role, content, session from chat order by id"])]
          (testing "ask! stores user then assistant messages for a session"
            (is (= 2 (count rows)) "Expected two chat rows (user + assistant)")
            (is (= ["user" "assistant"] (map :role rows)))
            (is (= ["Hello" "stub-reply"] (map :content rows)))
            (is (= ["test" "test"] (map :session rows)))))))))

(deftest validate-config-required-fields
  (testing "valid config passes"
    (is (= :ok (core/validate-config core/config))))
  (testing "missing config fails"
    (is (thrown? Exception
                 (core/validate-config {:db {:path ""} :node {:id ""} :ollama {:url "" :default_model ""}})))))

(deftest ollama-retries-then-succeeds
  (with-redefs [core/ollama-url "http://example.com/api/chat"]
    (let [calls  (atom 0)
          bodies (atom [])]
      (with-redefs [http/post (fn [url opts]
                                (swap! calls inc)
                                (swap! bodies conj {:url url :opts opts})
                                (if (< @calls 2)
                                  {:status 500 :body "fail"}
                                  {:status 200
                                   :body (json/write-str {:message {:content "ok"}})}))]
        (testing "ollama-generate posts to /api/chat with messages payload and retries once"
          (is (= "ok"
                 (core/ollama-generate {:model "m"
                                        :messages [{:role "user" :content "p"}]})))
          (is (= 2 @calls) "Should retry after first failure")
          (let [{:keys [url opts]} (last @bodies)
                body (json/read-str (:body opts) :key-fn keyword)]
            (is (= "http://example.com/api/chat" url))
            (is (= [{:role "user" :content "p"}] (:messages body)))
            (is (= "m" (:model body)))
            (is (= false (:stream body)))))))))

(deftest ollama-retries-while-loading
  (with-redefs [core/ollama-url "http://example.com/api/chat"]
    (let [calls  (atom 0)
          bodies (atom [])]
      (with-redefs [http/post (fn [url opts]
                                (swap! calls inc)
                                (swap! bodies conj {:url url :opts opts})
                                (if (< @calls 2)
                                  {:status 200
                                   :body (json/write-str {:done_reason "load"
                                                          :response ""})}
                                  {:status 200
                                   :body (json/write-str {:message {:content "ok"}})}))
                    core/retry* (fn [_ f _]
                                  ;; simulate retry: first attempt may throw, swallow, then retry
                                  (try
                                    (f)
                                    (catch Exception _
                                      (f))))]
        (testing "ollama-generate retries when model is loading"
          (is (= "ok"
                 (core/ollama-generate {:model "m"
                                        :messages [{:role "user" :content "p"}]})))
          (is (= 2 @calls) "Should retry after load signal")
          (let [{:keys [url opts]} (last @bodies)
                body (json/read-str (:body opts) :key-fn keyword)]
            (is (= "http://example.com/api/chat" url))
            (is (= [{:role "user" :content "p"}] (:messages body)))
            (is (= "m" (:model body)))
            (is (= false (:stream body)))))))))

(deftest summarize-old-history-when-over-limit
  (with-temp-ds
    (fn [ds]
      (let [summary-calls (atom 0)]
        (with-redefs [core/ollama-generate (fn [_]
                                             (swap! summary-calls inc)
                                             (str "summary-" @summary-calls))
                      core/history-limit 10
                      core/summary-chunk-size 10
                      core/summary-trigger-count 15]
          ;; Seed 21 chat messages to trigger one summary chunk (10 messages) at 1.5*N=15.
          (dotimes [i 21]
            (#'core/add-chat! "user" "m" (str "msg-" i) "sess"))
          (#'core/ensure-summaries! "sess" "m")
          (let [summaries (jdbc/execute! ds ["select start_id, end_id, summary from chat_summaries where session = 'sess' order by id"])]
            (is (= 1 (count summaries)) "Expected one summary chunk")
            (is (= 16 (inc (- (:end_id (first summaries)) (:start_id (first summaries))))))
            (is (= "summary-1" (:summary (first summaries))))
            (is (= 1 @summary-calls))))))))

(deftest self-edit-disabled-by-default
  (testing "self-edit endpoints reject when disabled"
    (is (false? (get-in core/config [:self_edit :enabled])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"self-edit is disabled"
         (milla.self-edit/list-files)))))
