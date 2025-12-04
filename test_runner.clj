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

(ns milla.test-runner
  (:require [clojure.test :as t]
            [milla.core-test])
  (:gen-class))

(defn -main
  "Run milla tests. Optionally pass namespaces on the command line.
   Exits non-zero on failure/error."
  [& nses]
  (let [targets (if (seq nses)
                  (map symbol nses)
                  ['milla.core-test])
        summary (apply t/run-tests targets)
        failures (+ (:fail summary 0) (:error summary 0))]
    (shutdown-agents)
    (when (pos? failures)
      (System/exit 1))))
