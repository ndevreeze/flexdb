# flexdb

A Clojure library designed to flexibly use a SQL DB  (SQLite and Postgres) with adding tables and columns (DDL) on the fly.

## Starting points

* Use should be easy - generate a database with tables/columns on-the-fly.
* Prefer keywords over strings in parameters and return values.
* Allow strings instead of keywords in parameters, but return keywords.

## Usage

FIXME

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

## Todo

* Documentation and examples - see tests for now
* Use protocols or multimethods for DB specific operations
* Speed profiling and fixing
* Also see source files

## License

Copyright © 2020 Nico de Vreeze

Distributed under the Eclipse Public License, the same as Clojure.
