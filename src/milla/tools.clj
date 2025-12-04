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

(ns milla.tools
  "User-extensible tools namespace.
   Add your own helper fns here; JVM/Clojure standard libraries are available.
   Keep interfaces small and pure when possible so they can be safely called
   from prompts or RAG helpers."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Example helpers (safe defaults). Extend or replace as needed.
(defn slurp-file
  "Read a UTF-8 file from disk. Use cautiously; prefer scoped paths."
  [path]
  (slurp (io/file path)))

(defn words
  "Split a string into lower-cased words."
  [s]
  (->> (str/split (or s "") #\"\\s+\")
       (remove str/blank?)
       (map str/lower-case)))
