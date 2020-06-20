(ns ndevreeze.flexdb-test-pg
  (:require [midje.sweet :as m]
            [me.raynes.fs :as fs]
            [java-time :as time]
            [clojure.java.jdbc :as j]
            [clojure.java.io :as io]
            [ndevreeze.flexdb :as db]
            [ndevreeze.flexdb-pg :as dpg]
            [ndevreeze.flexdb-test-lib :as tlib]))

(defn get-test-db-spec-pg
  "Get test database dependent on os"
  []
  (if (= (tlib/get-os) "Linux")
    ;; 5432 is default postgres port.
    (dpg/postgresql-spec "localhost" 5432 "test" "test" "test")
    (dpg/postgresql-spec "localhost" 5432 "test" "test" "test")
    ))

;; 2020-05-21: if you want to test without postgres.
#_(defn have-postgres-test-db1?
  "Return true iff the test database is available"
  []
  (println "No Postgres testing for now...")
  false)

(defn have-postgres-test-db1?
  "Return true iff the test database is available"
  []
  (println "Checking connection to Postgres test db...")
  (try
    (let [handle (db/open-db-spec (get-test-db-spec-pg))]
      (db/close-db handle)
      (println "Ok, do Postgres testing")
      true)
    (catch Exception e
      (println "Failed, no Postgres testing")
      false)))

(def have-postgres-test-db? (memoize have-postgres-test-db1?))

(defmacro test-in-new-db
  "Test some expression(s) in a new db
   handle-name - symbol to use in expr and return-expr
   return-expr - expr to evaluate and compare with midje.
   So this is a version with just return-expr, no prepare-expr."
  [handle-name return-expr]
  `(let [db-spec# (get-test-db-spec-pg)]
     (let [~handle-name (db/open-db-spec db-spec#)]
       (try
         (tlib/drop-test-tables ~handle-name)
         ~return-expr ;; this should be returned if no exception occurs.
         (catch Exception e# ;; TODO: maybe Postgres or SQL specific?
           (println "Exception: " e#)) ;; return nil iff exception occurs.
         (finally
           (db/close-db ~handle-name))) ;; does not get returned.
       )))

(m/fact "Test Postgres spec"
        (dpg/postgresql-spec "localhost" 5432 "test" "test" "test")
        => {:classname "org.postgresql.Driver"
            :enable_load_extension true
            :password "test"
            :subname "//localhost:5432/test"
            :subprotocol "postgresql"
            :user "test"})

(if (have-postgres-test-db?)
  (m/facts
   "test Postgres create table. With :serial, this is postgres specific."
   (m/fact "id-spec returns postgresql spec"
           (test-in-new-db handle
                           (db/id-spec handle))
           => [:id :serial :primary :key])

   (m/fact
    "Test percentile function"
    (test-in-new-db handle
                    (do
                      (db/in-transaction
                       handle
                       (doseq [i (range 100)]
                         (db/insert handle :ints {:value (inc i)}))
                       (db/exec handle "create table perc95 as
                                   select percentile_disc(.95)
                                      within group (order by value) perc
                                   from ints"))
                      (db/query handle "select perc from perc95")))
    => [{:perc 95}])

   (m/fact
    "Test sql-type within test-in-new-db"
    (test-in-new-db
     handle
     (db/sql-db-type (:db-spec @handle) (time/sql-date (time/local-date "2019-03-01")))
     => :date))

   (m/fact
    "db-values works"
    (db/db-values {:fld (time/local-date "2019-03-01")})
    => {:fld (time/local-date "2019-03-01")})

   ;; [2019-03-06 21:16] date/times different in SQLite and Postgres.
   ;; In Postgres a real date(time) field. Local or UTC time?
   ;; Should also work without using the 'T' in the middle. But this is not directly the concern of this library.
   (m/fact
    "Create DB without table, insert all field types, check result with query"
    (test-in-new-db
     handle
     (do
       (db/insert handle :testtable_date
                  {:cstr "abc"
                   :cint 20
                   :cfloat 3.14
                   :cdate (time/sql-date (time/local-date "2019-03-01"))
                   :cdt (time/sql-timestamp 
                         (time/local-date-time "2019-03-01T10:20:30.456"))})
       (db/query handle "select * from testtable_date")))
    => [{:cstr "abc",
         :cint 20,
         :cfloat 3.14,
         :cdate (time/sql-date (time/local-date "2019-03-01"))
         :cdt (time/sql-timestamp (time/local-date-time "2019-03-01T10:20:30.456"))
         :id 1}])

   (m/fact
    "Create DB without table, insert JSR 310 field types, check result with query"
    (test-in-new-db
     handle
     (do
       (db/insert handle :testtable_date310
                  {:cdate (time/local-date "2019-03-01")
                   :cldt (time/local-date-time "2019-03-01T10:20:30.456")

                   ;; this is UTC 09:20
                   :codt (time/offset-date-time "2019-03-01T09:20:30.456"
                                                (time/zone-offset 0))
                   ;; can also use CET offset:
                   :codt1 (time/offset-date-time "2019-03-01T10:20:30.456"
                                                 (time/zone-offset 1))                
                   ;; clock format 1551432030 -gmt 1 => Fri Mar 01 09:20:30 GMT 2019                
                   :cinst (time/instant 1551432030456)})
       (db/query handle "select * from testtable_date310")))
    => [{:cdate (time/sql-date (time/local-date "2019-03-01"))
         :cldt (time/sql-timestamp (time/local-date-time "2019-03-01T10:20:30.456"))

         ;; and while retrieving CET 10:20
         :codt (time/sql-timestamp 
                (time/local-date-time "2019-03-01T10:20:30.456"))

         :codt1 (time/sql-timestamp 
                 (time/local-date-time "2019-03-01T10:20:30.456"))
      
         :cinst (time/sql-timestamp 2019 3 1 10 20 30 456000000)
         :id 1}])

   ;; [2019-03-17 12:02] apparently also a java.lang.Integer in Postgres, not a Long.
   (m/fact "Create DB, add record, check class of generated id"
           (test-in-new-db handle
                           (class (db/insert handle :testtable {:column-1 "abc"
                                                                :column2 20})))
           => java.lang.Integer)

   ))

