(ns ndevreeze.flexdb-pg
  (:require [clojure.java.jdbc :as j]
            [clojure.set :as set]))

(defn postgresql-spec
  "Return a Postgresql DB spec for JDBC"
  [db-host db-port db-name user password]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (str "//" db-host ":" db-port "/" db-name)
   :user user
   :password password
   ;; enable loading things like percentile(), does this work for postgresql?
   :enable_load_extension true
   })
