(defproject ndevreeze/flexdb "0.4.2-SNAPSHOT"
  :description "Provide flexible database access, insert records
  without explicitly creating tables and columns first."
  :url "http://github.com/ndevreeze/flexdb"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 ;; [me.raynes/fs "1.4.6"] ;; file system functions
                 [clj-commons/fs "1.6.312"] ;; in place of me.raynes/fs.
                 [clojure.java-time "1.4.3"] ;; new in Java 8, replacing Joda-time
                 [org.flatland/ordered "1.15.12"]
                 [org.clojure/java.jdbc "0.7.12"]
                 ;; 2023-09-23: sqlite-jdbc might be bumped to
                 ;; 3.43.0.0 automatically with lein ancient. This
                 ;; removes some generated-id functionality, need to
                 ;; use 'RETURNING' clause, seems a bit more work.

                 ;; 2026-03-29: Newer versions, but test now fail, so try older versions.
                 ;; [org.xerial/sqlite-jdbc "3.51.3.0"] ;; SQLite ...
                 [org.postgresql/postgresql "42.7.10"] ;; ... and Postgres for now.

                 ;; [org.xerial/sqlite-jdbc "3.51.1.0"] ;; SQLite ...
                 ;; 2026-04-10: use 3.51.3.0, with buil-in
                 ;; percentile. Use own lib with ndv_percentile
                 ;; function.
                 [org.xerial/sqlite-jdbc "3.53.0.0"] ;; SQLite ...
                 ;; [org.postgresql/postgresql "42.7.8"] ;; ... and Postgres for now.

                 [ndevreeze/logger "0.6.2"]
                 ]
  :repl-options {:init-ns ndevreeze.flexdb}
  :profiles {:dev {:dependencies [[midje "1.10.10"]]}}
  :resource-paths ["resources"]

  :codox
  {:output-path "docs/api"
   :metadata {:doc/format :markdown}
   :source-uri "https://github.com/ndevreeze/flexdb/blob/master/{filepath}#L{line}"}

  :repositories [["releases" {:url "https://clojars.org/repo/" :creds :gpg}]]

  )
