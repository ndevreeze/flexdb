(defproject ndevreeze/flexdb "0.4.1-SNAPSHOT"
  :description "Provide flexible database access, insert records
  without explicitly creating tables and columns first."
  :url "http://github.com/ndevreeze/flexdb"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 ;; [me.raynes/fs "1.4.6"] ;; file system functions
                 [clj-commons/fs "1.6.310"] ;; in place of me.raynes/fs.
                 [clojure.java-time "1.2.0"] ;; new in Java 8, replacing Joda-time
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.xerial/sqlite-jdbc "3.42.0.0"] ;; SQLite ...
                 [org.postgresql/postgresql "42.6.0"] ;; ... and Postgres for now.
                 ]
  :repl-options {:init-ns ndevreeze.flexdb}
  :profiles {:dev {:dependencies [[midje "1.10.9"]]}}
  :resource-paths ["resources"]

  :codox
  {:output-path "docs/api"
   :metadata {:doc/format :markdown}
   :source-uri "https://github.com/ndevreeze/flexdb/blob/master/{filepath}#L{line}"}

  :repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  )
