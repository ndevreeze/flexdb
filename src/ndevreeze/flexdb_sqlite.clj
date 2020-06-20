(ns ndevreeze.flexdb-sqlite
  (:require [clojure.java.jdbc :as j]
            [clojure.set :as set]))

(defn sqlite-spec
  "Return a SQlite DB spec for JDBC, based on just db-name (=path)"
  [db-name]
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname db-name
   ;; enable loading things like percentile()
   :enable_load_extension true})
