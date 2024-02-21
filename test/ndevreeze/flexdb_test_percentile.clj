(ns ndevreeze.flexdb-test-percentile
  (:require [midje.sweet :as midje]
            [me.raynes.fs :as fs]
            [java-time :as time]
            [clojure.java.jdbc :as j]
            [clojure.java.io :as io]
            [ndevreeze.flexdb :as db]
            [ndevreeze.flexdb-sqlite :as dsqlite]
            [ndevreeze.flexdb-test-lib :as tlib]))

(defn get-test-db
  "Get test database using java.io.tmpdir property"
  []
  (fs/file (System/getProperty "java.io.tmpdir") "test-flexdb.db"))

(defn get-test-db-spec-sqlite
  "Get test database dependent on os"
  []
  (dsqlite/sqlite-spec (get-test-db)))

;; TODO: it would be nice to have these percentile modules available in the main library flexdb. However, this is another aspect, not really part of flexdb.
;; maybe add some generic functions for loading modules?

(defmacro test-in-new-db
  "Test some expression(s) in a new db.
   This version with only return-expr, should be enough in all cases.
   handle-name - symbol to use in expr and return-expr
   return-expr - expr to evaluate and compare with midje.
   SQLite version"
  [handle-name return-expr]
  `(let [db-name# (get-test-db)]
     (fs/delete db-name#)
     (let [~handle-name (db/open-db db-name#)]
       (try
         ~return-expr ;; this should be returned if no exception occurs.
         (catch org.sqlite.SQLiteException e#
           (println "Exception: " e#)) ;; return nil iff exception occurs.
         (finally
           (db/close-db ~handle-name))) ;; does not get returned.
       )))

;; 2024-02-21: Don't have the percentile library available yet on MacOS. So skip testing on MacOS for now.
(def TEST-PERCENTILE?
  (cond (= (System/getProperty "os.name") "Mac OS X") false
        (= (System/getProperty "os.name") "Linux") true
        :else false))

(when (not TEST-PERCENTILE?)
  (midje/fact "test-percentile-dummy"
              1  => 1))

(when TEST-PERCENTILE?
  ;; Use resources folder within this project folder.
  ;; io/resource  checks existence of the file.
  (defn- get-test-percentile-module-query
    "Get percentile module query: .so or .dll"
    []
    (if (= (tlib/get-os) "Linux")
      (format "SELECT load_extension('%s')" (tlib/to-local-path (io/resource "sqlite/linux64/percentile.so")))
      (format "SELECT load_extension('%s')" (tlib/to-local-path (io/resource "sqlite/win64/percentile.dll")))))

  (defn- load-percentile-module
    "Load percentile module. To be called as init-function for each new connection/transaction"
    [handle]
    (db/query handle (get-test-percentile-module-query)))

  ;; 2019-03-02: quite a lot of similarities between this SQLite version and
  ;; the Postgres one, so maybe should refactor.


  (midje/fact "Load percentile extension"
              (let [qload (get-test-percentile-module-query)]
                (println "Loading module with query:" qload)
                (test-in-new-db handle
                                (do
                                  (db/in-transaction
                                      handle
                                    (db/query handle qload))
                                  :ok))   ; result of query
                => :ok))


  ;; percentile query needs be done within same handle/connection as where it is
  ;; loaded.
  (midje/fact "Test percentile extension (1)"
              (test-in-new-db handle
                              (do
                                (db/in-transaction
                                    handle
                                  (db/query handle
                                            (get-test-percentile-module-query))
                                  (doseq [i (range 100)]
                                    (db/insert handle :ints {:value (inc i)}))
                                  (db/exec handle "create table perc95 as
                                               select percentile(value, 95) perc
                                               from ints"))
                                (db/query handle "select perc from perc95")))
              => [{:perc 95.05}])

  ;; check with declaring a function to always be executed for each new connection/transaction.
  (midje/fact "Test percentile extension (2)"
              (test-in-new-db handle
                              (do
                                (db/set-init-function
                                 handle
                                 #(db/query %
                                            (get-test-percentile-module-query)))
                                (db/in-transaction
                                    handle
                                  (doseq [i (range 100)]
                                    (db/insert handle :ints {:value (inc i)}))
                                  (db/exec handle "create table perc95 as
                                               select percentile(value, 95) perc
                                               from ints"))
                                (db/query handle "select perc from perc95")))
              => [{:perc 95.05}])

  (midje/fact "Test percentile extension (3)"
              (test-in-new-db handle
                              (do
                                (db/set-init-function handle load-percentile-module)
                                (db/in-transaction
                                    handle
                                  (doseq [i (range 100)]
                                    (db/insert handle :ints {:value (inc i)}))
                                  (db/exec handle "create table perc95 as
                                               select percentile(value, 95) perc
                                               from ints"))
                                (db/query handle "select perc from perc95")))
              => [{:perc 95.05}])

  )
