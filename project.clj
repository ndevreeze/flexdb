(defproject ndevreeze/flexdb "0.4.1-SNAPSHOT"
  :description "Provide flexible database access, insert records
  without explicitly creating tables and columns first."
  :url "http://github.com/ndevreeze/flexdb"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [me.raynes/fs "1.4.6"] ;; file system functions
                 [clojure.java-time "0.3.2"] ;; new in Java 8, replacing Joda-time
                 [org.clojure/java.jdbc "0.7.11"]
                 [org.xerial/sqlite-jdbc "3.30.1"] ;; SQLite ...
                 [org.postgresql/postgresql "42.2.9"] ;; ... and Postgres for now.
                 ]
  :repl-options {:init-ns ndevreeze.flexdb}
  :profiles {:dev {:dependencies [[midje "1.9.9"]]}}
  :resource-paths ["resources"]

  :repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  )
