(ns ndevreeze.flexdb-test-sqlite2
  (:require [midje.sweet :as midje]
            [me.raynes.fs :as fs]
            [java-time :as time]
            [clojure.java.jdbc :as j]
            [clojure.java.io :as io]
            [ndevreeze.flexdb :as db]
            [ndevreeze.flexdb-sqlite :as dsqlite]
            [ndevreeze.flexdb-test-sqlite :as tsqlite]
            [ndevreeze.flexdb-test-lib :as tlib]))

;; temp version with only the testcases that need fixing.
;; or maybe new ones.

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

;; 2023-09-23: temporary disabled, in [org.xerial/sqlite-jdbc "3.43.0.0"]
;; this seems to be different.
(midje/fact "Create DB, add record, check generated id"
            (test-in-new-db handle
                            (db/insert handle :testtable {:column-1 "abc"
                                                          :column2 20}))
            => 1)

;; 2023-09-23: also temporary disabled. same reason as above.
(midje/fact "Create DB, add record, check class of generated id"
            (test-in-new-db handle
                            (class (db/insert handle :testtable {:column-1 "abc"
                                                                 :column2 20})))
            => java.lang.Integer) ;; so not a long! 32 bits (Long is 64 bits).

;; macro to test both SQLite and Postgres
(defmacro fact-new-db
  "Expand fact with test-in-new-db with two items: one for SQLite and one for Postgres.
   The _ is for the =>
   For now more than one expr/result combination is not supported"
  [descr handle-name return-expr _ result]
  `(midje/fact ~descr
               (tsqlite/test-in-new-db ~handle-name ~return-expr) => ~result
               ))

(defmacro fact-db
  "Check a fact in 2 DB types"
  [descr handle-name expr _ result]
  `(midje/fact ~descr
               (let [db-spec# (tsqlite/get-test-db-spec-sqlite)
                     ~handle-name (db/open-db-spec db-spec#)]
                 ~expr) => ~result
               ))

(fact-db "Create DB with table, insert, check return value"
         handle
         (let [_ (tlib/drop-test-tables handle)
               _ (db/create-table handle :testtable [:column1])
               id (db/insert handle :testtable {:column1 "abc"})]
           (db/close-db handle)
           id) => 1)
