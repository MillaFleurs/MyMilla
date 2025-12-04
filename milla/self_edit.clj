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

(ns milla.self-edit
  "Guarded, minimal self-edit helpers. Disabled by default via config :self_edit {:enabled false}."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [milla.core :as core]))

(def ^:private root-dir
  (.getCanonicalFile (io/file ".")))

(defn enabled? []
  (true? (get-in core/config [:self_edit :enabled])))

(defn intent-required []
  (get-in core/config [:self_edit :intent] "rag-tools"))

(defn require-user-confirm? []
  (true? (get-in core/config [:self_edit :require_user_confirm])))

(defn allow-paths []
  (or (get-in core/config [:self_edit :allow_paths])
      ["src/milla/rag" "doc"]))

(defn- canonical-file [p]
  (.getCanonicalFile (io/file (core/expand-path p))))

(defn- rel-path [^java.io.File f]
  (let [root (.toPath root-dir)]
    (str (.normalize (.relativize root (.toPath f))))))

(defn allowed-path? [p]
  (let [f (canonical-file p)
        rel (rel-path f)]
    (and (.startsWith (.getPath f) (.getPath root-dir))
         (some (fn [prefix]
                 (or (= rel prefix)
                     (str/starts-with? (str rel) (str prefix "/"))))
               (allow-paths)))))

(defn list-files []
  (when-not (enabled?)
    (throw (ex-info "Self-edit is disabled" {:milla/error :self-edit-disabled})))
  (->> (file-seq root-dir)
       (filter #(.isFile ^java.io.File %))
       (map rel-path)
       (filter allowed-path?)
       sort))

(defn read-file [path]
  (when-not (enabled?)
    (throw (ex-info "Self-edit is disabled" {:milla/error :self-edit-disabled})))
  (when-not (allowed-path? path)
    (throw (ex-info "Path not allowed" {:milla/error :path-disallowed :path path})))
  {:path (rel-path (canonical-file path))
   :content (slurp (canonical-file path))})

(defn- write-with-backup! [^java.io.File f new-content]
  (let [parent (.getParentFile f)]
    (when parent (.mkdirs parent))
    (let [backup (io/file parent (str (.getName f) ".bak." (System/currentTimeMillis)))]
      (spit backup (slurp f))
      (spit f new-content)
      (.getPath backup))))

(defn apply-replace!
  "Safely replace the first occurrence of `old` with `new` in `path` if allowed.
   Returns {:path rel :backup backup-path}."
  [{:keys [path old new intent confirmed?]}]
  (when-not (enabled?)
    (throw (ex-info "Self-edit is disabled" {:milla/error :self-edit-disabled})))
  (let [expected (intent-required)]
    (when (or (nil? intent) (not= intent expected))
      (throw (ex-info "Self-edit intent not allowed" {:milla/error :intent-disallowed
                                                     :expected expected
                                                     :provided intent}))))
  (when (and (require-user-confirm?) (not confirmed?))
    (throw (ex-info "User confirmation required" {:milla/error :confirmation-required})))
  (when (or (str/blank? path) (str/blank? old))
    (throw (ex-info "path and old must be provided" {:milla/error :invalid-request})))
  (when-not (allowed-path? path)
    (throw (ex-info "Path not allowed" {:milla/error :path-disallowed :path path})))
  (let [f (canonical-file path)
        content (slurp f)]
    (if (str/includes? content old)
      (let [updated (str/replace-first content old (or new ""))
            backup (write-with-backup! f updated)]
        {:path (rel-path f)
         :backup backup})
      (throw (ex-info "Old text not found" {:milla/error :old-not-found :path path})))))
