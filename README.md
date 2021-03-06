# flexdb

A Clojure library designed to flexibly use a SQL DB  (SQLite and Postgres) with adding tables and columns (DDL) on the fly.

## Installation

Leiningen/Boot

    [ndevreeze/flexdb "0.4.0"]

Clojure CLI/deps.edn

    ndevreeze/flexdb {:mvn/version "0.4.0"}

[![Clojars Project](https://img.shields.io/clojars/v/ndevreeze/flexdb.svg)](https://clojars.org/ndevreeze/flexdb)

## Usage

    (require '[me.raynes.fs :as fs])
    (require '[ndevreeze.flexdb :as db])

    (let [db-name "/tmp/test-db.db"
          _ (fs/delete db-name)
          db (db/open-db-sqlite db-name)]
      (db/insert db :testtable {:col "Value of column"})
      (println "Result of db/query: " (db/query db "select * from testtable"))
      (db/close-db db))

## Testing

Functional tests:

    $ lein midje
    WARNING: any? already refers to: #'clojure.core/any? in namespace: leiningen.midje, being replaced by: #'leiningen.midje/any?
    Loading module with query: SELECT   load_extension('/path/to/flexdb/resources/sqlite/linux64/percentile.so')
    Checking connection to Postgres test db...
    Ok, do Postgres testing
    nil
    All checks (105) succeeded.

Speed test:

    ndevreeze.flexdb> (ndevreeze.flexdb-test/test-speed "/tmp/test-speed-clj.db")
    Starting test
    Testing with #iterations:  10
    Testing with #iterations:  100
    Testing with #iterations:  1000
    Testing with #iterations:  10000
    #iterations: 10000
    Seconds taken:  15
    #Iter/sec:  666.6666666666667

## Starting points

* Use should be easy - generate a database with tables/columns on-the-fly.
* Prefer keywords over strings in parameters and return values.
* Allow strings instead of keywords in parameters, but return keywords.

## Todo

* Documentation and examples (both usage and design) - see tests for now
* Use protocols or multimethods for DB specific operations
* Speed profiling and fixing
* Multi-insert
* Using db as dynamic var for convenience.
* Also see source files

## Related and similar projects (libraries)

* Datomic?
* ORMs such as Korma - Mostly these focus either on the DDL or DML part, not both.

## License

Copyright © 2020 Nico de Vreeze

Distributed under the Eclipse Public License, the same as Clojure.
