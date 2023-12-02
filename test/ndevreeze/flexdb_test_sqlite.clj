(ns ndevreeze.flexdb-test-sqlite
  (:require [midje.sweet :as midje]
            [me.raynes.fs :as fs]
            [java-time :as time]
            [clojure.java.jdbc :as j]
            [clojure.java.io :as io]
            [ndevreeze.flexdb :as db]
            [ndevreeze.flexdb-sqlite :as dsqlite]
            [ndevreeze.flexdb-test-lib :as tlib]))

;; TODO: possibly better with a protocol, which can be extended to support other OS-es.
;; TODO: use config to set test-locations, also dependent on OS. Or override through
;;       env-var?
(defn get-test-db
  "Get test database dependent on os"
  []
  (if (= (tlib/get-os) "Linux")
    "/tmp/test-flexdb.db"
    (do
      (fs/mkdir "c:/tmp")
      "c:/tmp/test-flexdb2.db")))

(defn get-test-db-spec-sqlite
  "Get test database dependent on os"
  []
  (dsqlite/sqlite-spec (get-test-db)))

;; TODO: it would be nice to have these percentile modules available in the main library dynamicdb. However, this is another aspect, not really part of dynamicdb.
;; maybe add some generic functions for loading modules?

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

;; 2023-12-02: TODO: should set *debug* var or def and define
;; test-in-new-db as real or no-op macro.

;; 2019-03-02: quite a lot of similarities between this SQLite version and
;; the Postgres one, so maybe should refactor.
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

(midje/fact "sqlite-spec"
            (dsqlite/sqlite-spec (get-test-db)) =>
            {:classname "org.sqlite.JDBC"
             :subprotocol "sqlite"
             :subname (get-test-db)
             :enable_load_extension true})

;; 2019-03-02: This one not applicable for Postgres: DB already exists, is not a file.
(midje/fact "Create DB (1)"
            (let [db-name (get-test-db)]
              (fs/delete db-name)
              (let [handle (db/open-db db-name)]
                (db/close-db handle))
              (fs/exists? db-name)) => true)

;; this one does not work with postgres, so use question marks for parameters in other tests.
;; so this one specific for SQLite.
(midje/fact "Create DB with table, add record with existing columns, query with params 2"
            (test-in-new-db handle
                            (do
                              (db/create-table handle :testtable
                                               [:column_1 [:column2 :integer]])
                              (db/insert handle :testtable {:column-1 "abc"
                                                            :column2 20})
                              (db/query handle
                                        "select * from testtable where column2 = :param"
                                        [20])))
            => [{:column_1 "abc", :column2 20 :id 1}])

;; [2019-03-17 11:48] some issues with sqlite data types for foreign keys, so separate tests.
(midje/fact "Create DB, add record, query with params"
            (test-in-new-db handle
                            (do
                              (db/insert handle :testtable {:column-1 "abc"
                                                            :column2 20})
                              (db/query handle
                                        "select * from testtable where column2 = :param"
                                        [20])))
            => [{:column_1 "abc", :column2 20 :id 1}])

;; 2023-09-23: temporary disabled, in [org.xerial/sqlite-jdbc "3.43.0.0"]
;; this seems to be different.
;; 2023-11-30: this test still succeeds, with nothing changed. So prb not a good test.
(midje/fact "Create DB, add record, check generated id"
            (test-in-new-db handle
                            (db/insert handle :testtable {:column-1 "abc"
                                                          :column2 20}))
            => 1)

;; 2023-11-30: need another test-case: add 2 rows, check the second and has generated id=2.
;; 2023-11-30: this one indeed fails.
(midje/fact "Create DB, add 2 records, check last generated id"
            (test-in-new-db handle
                            (do
                              (db/insert handle :testtable {:column-1 "abc"
                                                            :column2 20})
                              (db/insert handle :testtable {:column-1 "abcde"
                                                            :column2 30})))
            => 2)



;; 2023-09-23: also temporary disabled. same reason as above.
(midje/fact :gen-id "Create DB, add record, check class of generated id"
            (test-in-new-db handle
                            (class (db/insert handle :testtable {:column-1 "abc"
                                                                 :column2 20})))
            => java.lang.Integer) ;; so not a long! 32 bits (Long is 64 bits).

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
 
;; [2019-03-06 21:16] date/times different in SQLite and Postgres.
;; In SQLite just a string.
;; Should also work without using the 'T' in the middle. But this is not directly the concern of this library.
(midje/fact
 "Create DB without table, insert all field types, check result with query"
 (test-in-new-db
  handle
  (do
    (db/insert handle :testtable {:colstr "abc"
                                  :colint 20
                                  :colfloat 3.14
                                  :coldate (time/sql-date (time/local-date "2019-03-01"))
                                  :coldt (time/sql-timestamp 
                                          (time/local-date-time "2019-03-01T10:20:30.456"))
                                  })
    (db/query handle "select * from testtable")))
 => [{:colstr "abc"
      :colint 20
      :colfloat 3.14
      :coldate 1551394800000
      :coldt 1551432030456
      :id 1}])

(midje/fact
 "Create DB without table, insert JSR 310 types, check result with query.
  These JSR 310 types are stored as date-time strings"
 (test-in-new-db
  handle
  (do
    (db/insert handle :testtable
               {:cdate (time/local-date "2019-03-01")
                :cldt (time/local-date-time "2019-03-01T10:20:30")
                
                ;; this is UTC 09:20
                :codt (time/offset-date-time "2019-03-01T09:20:30.456"
                                             (time/zone-offset 0))
                ;; can also use CET offset:
                :codt1 (time/offset-date-time "2019-03-01T10:20:30.456"
                                              (time/zone-offset 1))                

                ;; clock format 1551432030 -gmt 1 => Fri Mar 01 09:20:30 GMT 2019
                :cinst (time/instant 1551432030456)
                })
    (db/query handle "select * from testtable")))
 => [{:cdate "2019-03-01"
      :cldt "2019-03-01T10:20:30"
      :codt "2019-03-01T09:20:30.456Z"
      :codt1 "2019-03-01T10:20:30.456+01:00"

      ;; clock format 1551432030 -gmt 1 => Fri Mar 01 09:20:30 GMT 2019
      :cinst "2019-03-01T09:20:30.456Z"
      
      :id 1}])

;; 2023-12-02: to test a single fact, or just with the given tags:
;; (autotest :filter :gen-id)
(midje/fact :gen-id "test2b - Create DB, add 2 records, check last generated id"
            (test-in-new-db handle
                            (do
                              (println "Inserting 2 records")
                              (db/insert handle :testtable {:column-1 "abc"
                                                            :column2 20})
                              (db/insert handle :testtable {:column-1 "abcde"
                                                            :column2 30})))
            => 2)
