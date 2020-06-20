(ns ndevreeze.flexdb-test-lib
  (:require [ndevreeze.flexdb :as db]
            [midje.sweet :as m]
            [me.raynes.fs :as fs]
            [java-time :as time]
            [clojure.java.jdbc :as j]
            [clojure.java.io :as io]))

(defn get-os
  "Get operating system: Linux or Windows"
  []
  (System/getProperty "os.name"))

;; could/should check if URL is a file: URL.
(defn to-local-path
  "Convert file: URL to local absolute path, eg for use in sqlite load_extension.
   Use forward slashes (/) for both Linux and Windows, SQLite needs this."
  [url]
  (-> url
      io/as-file
      str
      (clojure.string/replace \\ \/)))

(defn drop-test-tables [handle]
  (doseq [table ["testtable" "testtable1" "testtable2" "testtable_date" "testtable_date310"
                 "ints" "perc95" "to" "testtableto"]]
    (db/exec handle (format "drop table if exists \"%s\"" table))))
