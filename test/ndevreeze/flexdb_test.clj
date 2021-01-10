(ns ndevreeze.flexdb-test
  (:require [ndevreeze.flexdb :as db]
            [midje.sweet :as midje]
            [me.raynes.fs :as fs]
            [java-time :as time]
            [clojure.java.jdbc :as j]
            [clojure.java.io :as io]
            [ndevreeze.flexdb-test-pg :as tpg]
            [ndevreeze.flexdb-test-sqlite :as tsqlite]
            [ndevreeze.flexdb-test-lib :as tlib]))

;; macro to test both SQLite and Postgres
(defmacro fact-new-db
  "Expand fact with test-in-new-db with two items: one for SQLite and one for Postgres.
   The _ is for the =>
   For now more than one expr/result combination is not supported"
  [descr handle-name return-expr _ result]
  `(midje/fact ~descr
               (tsqlite/test-in-new-db ~handle-name ~return-expr) => ~result
               (if (tpg/have-postgres-test-db?)
                 (tpg/test-in-new-db ~handle-name ~return-expr) => ~result)
               ))

(defmacro fact-db
  "Check a fact in 2 DB types"
  [descr handle-name expr _ result]
  `(midje/fact ~descr
               (let [db-spec# (tsqlite/get-test-db-spec-sqlite)
                     ~handle-name (db/open-db-spec db-spec#)]
                 ~expr) => ~result
               (if (tpg/have-postgres-test-db?)
                 (let [db-spec# (tpg/get-test-db-spec-pg)
                       ~handle-name (db/open-db-spec db-spec#)]
                   ~expr) => ~result)))

(fact-db "Open and close DB, check open field (1)"
         handle
         (let [open? (:open? @handle)]
           (db/close-db handle)
           open?) => true)

(fact-db "Open and close DB, check open field (2)"
         handle
         (do 
           (db/close-db handle)
           (:open? @handle)) => false)

;; 2020-05-21: some facts without db.
(midje/fact "Test column-name returns :keyword"
            (db/column-name "abc")
            => :abc)

(midje/fact "Test column-name returns :keyword"
            (db/column-name ["abc" "varchar"])
            => :abc)

;; TODO use this generic function when working within Postgres
(fact-db "Create DB with table, insert, check return value"
         handle
         (let [_ (tlib/drop-test-tables handle)
               _ (db/create-table handle :testtable [:column1])
               id (db/insert handle :testtable {:column1 "abc"})]
           (db/close-db handle)
           id) => 1)

(fact-new-db "Create DB with table, check existence (1a)"
              handle
              (do
                (db/create-table handle :testtable1 [:column1 :column2])
                (db/table-exists? handle "testtable1"))
              => true)

(fact-new-db "Create DB with table, check existence of other table (1b)"
              handle
              (do
                (db/create-table handle :testtable1 [:column1 :column2])
                (db/table-exists? handle "testtable2"))
              => false)

(fact-new-db "Create DB with table, check existence (2)"
              handle
              (do
                (db/create-table handle :testtable2 [:column1 :column2])
                (db/table-exists? handle :testtable2))
             => true)

(fact-new-db "Create DB with table, check tables function"
              handle
              (do
                (db/create-table handle :testtable [:column1 :column2])
                (db/tables handle))
              => #{:testtable})

(fact-new-db "Create DB with table, check columns function"
              handle
              (do
                (db/create-table handle :testtable [:column1 :column2])
                (db/columns handle "testtable"))
              => #{:id :column1 :column2})

(fact-new-db "Create DB with table, check column-exists? function (1)"
              handle
              (do
                (db/create-table handle :testtable [:column1 :column2])
                (db/column-exists? handle "testtable" "column1"))
              => true)
            
(fact-new-db "Create DB with table, check column-exists? function (2)"
              handle
              (do
                (db/create-table handle :testtable [:column1 :column2])
                (db/column-exists? handle :testtable :column1))
              => true)

(fact-new-db "Create DB with table, alter/add column, check columns function"
             handle
             (do
               (db/create-table handle :testtable [:column1 :column2])
               (db/alter-table handle :testtable [:column3])
               (db/columns handle "testtable"))
             => #{:id :column1 :column2 :column3})

(fact-new-db "Create DB, alter/add column, check columns function"
              handle
              (do
                (db/alter-table handle :testtable [:column3])
                (db/columns handle "testtable"))
              => #{:id :column3})

(fact-new-db "Create DB with table, add record with existing columns"
             handle
             (do
               (db/create-table handle :testtable
                                [:column_1 [:column2 :integer]])
               (db/insert handle :testtable {:column-1 "abc"
                                             :column2 20})
               (db/query handle "select * from testtable"))
             => [{:column_1 "abc", :column2 20, :id 1}])

(fact-new-db "Create DB with table, add record with existing columns, query with params"
             handle
             (do
               (db/create-table handle :testtable
                                [:column_1 [:column2 :integer]])
               (db/insert handle :testtable {:column-1 "abc"
                                             :column2 20})
               (db/query handle "select * from testtable where column2 = ?" [20]))
             => [{:column_1 "abc", :column2 20, :id 1}])

(fact-new-db "Create DB with table, add record with existing
        columns, query with 2 params"
             handle
             (do
               (db/create-table handle :testtable
                                [:column_1 [:column2 :integer]])
               (db/insert handle :testtable {:column-1 "abc"
                                             :column2 20})
               (db/query handle "select * from testtable where column2 = ? and column_1 = ?"
                         [20 "abc"]))
             => [{:column_1 "abc", :column2 20, :id 1}])

(fact-new-db "Create DB with table, add column, add record with existing columns"
             handle
             (do
               (db/create-table handle :testtable
                                [:column1 [:column2 :integer]])
               (db/alter-table handle :testtable [[:column3 :float]])
               (db/insert handle :testtable {:column1 "abc"
                                             :column2 20
                                             :column3 3.14})
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :column2 20, :column3 3.14, :id 1}])

(fact-new-db "Create DB with table, insert with exec, check result with query"
             handle
             (do
               (db/create-table handle :testtable
                                [:column1 [:column2 :integer]])
               (db/exec handle
                        "insert into testtable (column1, column2) values ('abcd', 23)")
               (db/query handle "select * from testtable"))
             => [{:column1 "abcd", :column2 23, :id 1}])

(fact-new-db "Create DB without table, insert, check result with query"
              handle
              (do
                (db/insert handle :testtable {:column1 "abc"
                                              :column2 20
                                              :column3 3.14})
                (db/query handle "select * from testtable"))
              => [{:column1 "abc", :column2 20, :column3 3.14, :id 1}])

(fact-new-db "Create DB without table, insert with :id field, check result with query"
              handle
              (do
                (db/insert handle :testtable {:id 22
                                              :column1 "abc"
                                              :column2 20
                                              :column3 3.14})
                (db/query handle "select * from testtable"))
              => [{:column1 "abc", :column2 20, :column3 3.14, :id 22 :id_ 1}])

(fact-new-db "Create DB without table, insert including nulls, check result with query"
              handle
              (do
                (db/insert handle :testtable {:column1 "abc"
                                              :column2 20
                                              :column3 3.14
                                              :column4 nil})
                (db/query handle "select * from testtable"))
             => [{:column1 "abc", :column2 20, :column3 3.14, :column4 nil, :id 1}])

(fact-new-db "Create DB without table, insert in 2 tables, check result with query"
             handle
             (do
               (db/insert handle :testtable {:column1 "abc"
                                             :column2 20
                                             :column3 3.14})
               (db/insert handle :testtable2 {:column1 "abc"
                                              :column2 20
                                              :column3 3.14})
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :column2 20, :column3 3.14, :id 1}])

(fact-new-db "Check alter-table when no new columns given"
             handle
             (do
               (db/create-table handle :testtable
                                [:column1 [:column2 :integer]])
               (db/alter-table handle :testtable nil))
             => nil)

(fact-new-db "Create DB without table, insert in 1 table within trans, check result with query"
             handle
             (do
               (binding [db/*DEBUG* false]
                 (db/in-transaction
                  handle
                  (db/insert handle :testtable {:column1 "abc"
                                                :column2 20
                                                :column3 3.14})))
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :column2 20, :column3 3.14, :id 1}])

(fact-new-db "Create DB without table, insert in 1 table within trans, check columns"
             handle
             (do
               (binding [db/*DEBUG* false]
                 (db/in-transaction
                  handle
                  (db/insert handle :testtable {:column1 "abc"
                                                :column2 20
                                                :column3 3.14})))
               (db/columns handle :testtable))
             => #{:id :column2 :column3 :column1})

(fact-new-db "Create DB without table, insert in 2 tables within trans, check result with query"
             handle
             (do
               (binding [db/*DEBUG* false]
                 (db/in-transaction
                  handle
                  (db/insert handle :testtable {:column1 "abc"
                                                :column2 20
                                                :column3 3.14})
                  (db/insert handle :testtable2 {:column1 "abc"
                                                 :column2 20
                                                 :column3 3.14})))
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :column2 20, :column3 3.14, :id 1}])

(fact-new-db "Create DB no tables, insert 2x in 1 table within trans,
             different columns, check result with query"
             handle
             (do
               (binding [db/*DEBUG* false]
                 (db/in-transaction
                  handle
                  (db/insert handle :testtable {:column1 "abc"
                                                :column2 20
                                                :column3 3.141})
                  (db/insert handle :testtable {:column1 "abc"
                                                :column2 20
                                                :column4 3.142})))
               (db/query handle "select * from testtable limit 1"))
             => [{:column1 "abc", :column2 20, :column3 3.141, :column4 nil, :id 1}])
 
(fact-new-db "Create DB with table, check metadata/columns"
              handle
              (do
                (db/create-table handle :testtable [:column1])
                (db/columns handle :testtable))
              => #{:id :column1})

(fact-new-db "Create DB with table and reserved word as column, check
              metadata/columns"
             handle
              (do
                (db/create-table handle :testtableto [:to])
                (db/columns handle :testtableto))
              => #{:id :to})

(fact-new-db "Create DB with table and reserved word as tablename,
              check metadata/columns"
             handle
              (do
                (db/create-table handle :to [:column1])
                (db/columns handle :to))
              => #{:id :column1})

(fact-new-db "Create DB with table, insert with extra column, check
             result with query"
             handle
             (do
               (db/create-table handle :testtable [:column1])
               (db/insert handle :testtable {:column1 "abc"
                                             :column2 20
                                             :column3 3.14})
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :column2 20, :column3 3.14, :id 1}])

(fact-new-db "Create DB with table, insert with extra column with
             reserved word, check result with query"
             handle
             (do
               (db/create-table handle :testtable [:column1])
               (db/insert handle :testtable {:column1 "abc"
                                             :to 20
                                             :column3 3.14})
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :to 20, :column3 3.14, :id 1}])

(fact-new-db "Create DB, insert with column with reserved word, check
             result with query"
             handle
             (do
               (db/insert handle :testtable {:column1 "abc"
                                             :to 20
                                             :column3 3.15})
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :to 20, :column3 3.15, :id 1}])

;; with insert-no-check the table must already exist with the correct columns.
(fact-new-db "Create DB with table, insert-no-check with column with reserved word, check
             result with query"
             handle
             (do
               (db/create-table handle :testtable [:column1 [:to :integer] [:column3 :float]])
               (db/insert-no-check handle :testtable {:column1 "abc"
                                                      :to 20
                                                      :column3 3.15})
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :to 20, :column3 3.15, :id 1}])

(fact-new-db "Create DB with table, insert-opt with column with reserved word, check
             result with query"
             handle
             (do
               (db/insert-opt handle :testtable {:column1 "abc"
                                                      :to 20
                                                      :column3 3.15})
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :to 20, :column3 3.15, :id 1}])

(fact-new-db "Create DB, insert with table with reserved word, check
             result with query"
             handle
             (do
               (db/insert handle :to {:column1 "abc"
                                      :to22 20
                                      :column3 3.15})
               (db/query handle "select * from \"to\""))
             => [{:column1 "abc", :to22 20, :column3 3.15, :id 1}])

;; with insert-no-check the table must already exist with the correct columns.
(fact-new-db "Create DB with table, insert-no-check with table with
             reserved word, check result with query"
             handle
             (do
               (db/create-table handle :to [:column1 [:to23 :integer] [:column3 :float]])
               (db/insert-no-check handle :to {:column1 "abc"
                                               :to23 20
                                               :column3 3.15})
               (db/query handle "select * from \"to\""))
             => [{:column1 "abc", :to23 20, :column3 3.15, :id 1}])

(fact-new-db "Create DB with table, insert-opt with table with
             reserved word, check result with query"
             handle
             (do
               (db/insert-opt handle :to {:column1 "abc"
                                          :to24 20
                                          :column3 3.15})
               (db/query handle "select * from \"to\""))
             => [{:column1 "abc", :to24 20, :column3 3.15, :id 1}])

(fact-new-db "Create DB with table, insert with extra column in
             transaction, check result with query"
             handle
             (do
               (db/create-table handle :testtable [:column1])
               (db/in-transaction
                handle
                (db/insert handle :testtable {:column1 "abc"
                                              :column2 20
                                              :column3 3.14}))
               (db/query handle "select * from testtable"))
             => [{:column1 "abc", :column2 20, :column3 3.14, :id 1}])

(fact-new-db "Starting nested transactions fails"
             handle
             (try
               (db/create-table handle :testtable [:column1])
               (db/in-transaction
                handle
                (db/in-transaction
                 handle
                 (db/insert handle :testtable {:column1 "abc"
                                               :column2 20
                                               :column3 3.14})))
               (db/query handle "select * from testtable")
               (catch Exception e :exception-started))
             => :exception-started)

(fact-new-db "Test doseq insert"
             handle
             (do
               (db/in-transaction
                handle
                (doseq [i (range 2)]
                  (db/insert handle :ints {:value (inc i)})))
               (db/query handle "select value from ints limit 1"))
             => [{:value 1}])

(midje/tabular
 (midje/fact "sql-db-type works for different db types"
             (db/sql-db-type {:subprotocol ?db-type} ?val) => ?expected)
 ?db-type ?val ?expected
 "sqlite" 1    :integer
 "sqlite" "abc" :varchar
 "postgresql" (time/sql-date (time/local-date "2019-03-01")) :date
 ;; [2020-05-19 19:40] also check null/nil
 "sqlite" nil :varchar
 "postgresql" nil :varchar
 )

(defn test-insert-iterations
  [handle niterations]
  (println "Testing with #iterations: " niterations)
  (let [start-inst (time/instant)]
    (db/in-transaction
     handle
     (doseq [i (range niterations)]
       (db/insert handle :testtable {:column1 (str "abc" i)
                                     :column2 (str "def" i)
                                     :column3 (str "ghi" i)})))
    (time/as (time/duration start-inst (time/instant)) :seconds)))

;; TODO - 2019-03-02: maybe these speed-tests in seperate namespace.
;; Don't call this one as a test each time, because it takes at least 10 seconds.
;; call from REPL:
;; (ndevreeze.flexdb-test/test-speed "/tmp/test-speed-clj.db")
;; maybe could build this as a stream, taking values as long as time < 10 seconds. Or taking first that is > 10 seconds.
(defn test-speed
  "Test speed of inserting, maybe with transaction and prepared statements
   First test with 10 records, multiply by 10 each time, as long as time
   taken is less than 10 seconds. Report speed of last run"
  [db-name]
  (println "Starting test")
  (fs/delete db-name)
  (let [handle (db/open-db db-name)]
    (db/insert handle :testtable {:column1 "abc" :column2 "def" :column3 "ghi"})
    (loop [niterations 1
           sec-taken 0]
      (if (< sec-taken 10)
        (recur (* 10 niterations)
               (test-insert-iterations handle (* 10 niterations)))
        (do
          (println "#iterations:" niterations)
          (println "Seconds taken: " sec-taken)
          (println "#Iter/sec: " (* 1.0 (/ niterations sec-taken))))))
    (db/close-db handle)))
