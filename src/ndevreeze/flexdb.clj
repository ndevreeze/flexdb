(ns ndevreeze.flexdb
  (:require [clojure.java.jdbc :as j]
            [clojure.set :as set]
            [clojure.string :as str]
            [java-time :as time]
            [flatland.ordered.map :as omap]
            [flatland.ordered.set :as oset]
            [ndevreeze.logger :as log]
            [ndevreeze.flexdb-sqlite :as dsqlite]
            ;; 2025-10-29: had duplicate flexdb-sqlite below. Replaced
            ;; with flexdb-pg, but never used. Makes sense.
            ;; [ndevreeze.flexdb-pg :as dpg]

            [me.raynes.fs :as fs]))

;; Starting points:
;; * Use should be easy - generate a database with tables/columns on-the-fly.
;; * Prefer keywords over strings in parameters and return values.
;; * Allow strings instead of keywords in parameters, but return keywords.

;; set to true to print more debugging info.
(def ^:dynamic *DEBUG* false)
;; (def ^:dynamic *DEBUG* true)

(defn version
  "Return current version of library"
  []
  "ndevreeze/flexdb v0.4.2-SNAPSHOT, 2025-11-05, with ordered-map")

(declare table-exists?)

;; SQLite specific version, should be renamed and orig one deprecated.
;; maybe move to sqlite namespace, but this is more convenient for the user.
(defn open-db-sqlite
  "Open SQLite DB and return handle (SQLiteConnection for now, could be changed.
   Return connection both directly in map as within returned db-spec within map.
   db-name - path to DB, existing or new, as string"
  [db-name]
  (log/debug "FlexDB - Opening SQLite DB:" db-name)
  (let [db-spec (dsqlite/sqlite-spec db-name)
        conn (j/get-connection db-spec)]
    (atom {:conn conn
           :open? true
           :db-spec db-spec
           :init-function nil ;; function to exec for each new connection/transaction.
           })))

(def open-db
  "Deprecated, use open-db-sqlite"
  open-db-sqlite)

(defn open-db-spec
  "Open DB connection given a spec, could be SQLite or Postgresql, maybe others"
  [db-spec]
  (let [conn (j/get-connection db-spec)]
    (atom {:conn conn
           :open? true
           :db-spec db-spec ;; don't add connection, fails with metadata, conn is closed.
           :init-function nil ;; function to exec for each new connection/transaction.
           })))

(defn close-db
  "Close a DB handle (SQLiteConnection or Postgres for now, could be changed)
   Could contain prepared statements and meta-data in the future"
  [db-handle]
  (.close (:conn @db-handle))
  (swap! db-handle merge {:open? false})
  db-handle)

(defn set-init-function
  "Set a function to be executed on each new connection/transaction.
   Function should accept one parameter, the db handle."
  [db-handle f]
  (swap! db-handle merge {:init-function f}))

(defn in-transaction?
  "Return true if handle is currently within a transaction"
  [db-handle]
  (some? (:transaction @db-handle)))

(defn set-transaction
  "Mark transaction handle given by with-db-transaction in this handle.
   Also exec init-function on this transaction iff it is set.
   Keep list of new tables/columns created in this transaction.
   Also call :init-function when given (eg. for percentile function).
   Fail if transaction already started"
  [db-handle t-con]
  (swap! db-handle #(if-not (in-transaction? db-handle)
                      (assoc % :transaction t-con :new-columns {})
                      (throw (Exception. "Transaction already started"))))
  (when-let [init-f (:init-function @db-handle)]
    (init-f db-handle)))

(defn unset-transaction
  "Unmark transaction handle given by with-db-transaction in this handle.
   Reset new columns/tables, should be available als meta-data after commit.
   Fail if no transaction started"
  [db-handle]
  (swap! db-handle #(if (in-transaction? db-handle)
                      (dissoc % :transaction :new-columns)
                      (throw (Exception. "No transaction started")))))

;; probably don't use for meta-data things.
;; 2018-07-31: true-ish, add new tables/columns to own state here.
(defn current-connection
  "Return current connection/transaction handle, or the db-spec if none exists.
   Use for working within transactions."
  [db-handle]
  (cond (some? (:transaction @db-handle))
        (:transaction @db-handle)
        :else
        (:db-spec @db-handle)
        ))

(defmacro in-transaction
  "Run body expressions in a transaction based on handle.
   Works in conjunction with set-transaction and current-transaction.
   Returns result of last expression"
  [handle & body]
  `(j/with-db-transaction [t-con# (:db-spec @~handle)]
     (db/set-transaction ~handle t-con#)
     (try
       ~@body
       (finally 
         (db/unset-transaction ~handle)))))

;; 2020-05-21: this one not used, both SQLite and Postgress understand
;; double-quoted tables and columns.
(defn surround-brackets
  "Convert par to a string and surround it with brackets.
   Wrt bracketed table and column names for reserved words"
  [par]
  (cond (string? par) (str "[" par "]")
        (keyword? par) (str "[" (name par) "]")
        :else (str "[" par "]")))

(defn dquoted
  "Convert par to a string and surround it with double quotes.
   With respect to bracketed table and column names for reserved words.
   This seems to work both in SQLite and Postgres (Postgres does not like brackets)"
  [par]
  (cond (string? par) (str "\"" par "\"")
        (keyword? par) (str "\"" (name par) "\"")
        :else (str "\"" par "\"")))

(defn- column-spec
  "Create column spec including type for create/alter table
   column - either a string/keyword (then default datatype: string),
           or seq-of [:name :type]
   return vector with name (double-quoted-string) and type (keyword).
   Enclose name in double quotes wrt keywords (only for keywords?)
   If column is already a seq of 2 elements, still surround the first one with dquotes."
  [column]
  (if (or (string? column) (keyword? column))
    [(dquoted column) :varchar]
    (cons (dquoted (first column)) (rest column))))
 
;; 2020-05-21: public function, so it can be tested.
(defn column-name
  "Return column-name as :keyword for column
   column - either a string/keyword (then default datatype: string),
           or seq-of [:name :type]
   return vector with name and type.
   Used for internal map with new tables/columns within transaction."
  [column]
  (cond (string? column) (keyword column)
        (keyword? column) column
        :else (keyword (first column))))

(defn- add-table-columns
  "Add table and columns to :new-columns within map m and return this map.
   Table - string or keyword
   Columns - sequence of column-specs (string, keyword, seq-of name, type."
  [m table columns]
  (update-in m [:new-columns (keyword table)]
             #(set/union % (oset/into-ordered-set (map column-name columns)))))

;; TODO - use protocol, multimethod or similar for this?
(defn id-spec
  "Generate id-column spec for database type, given db handle"
  ([handle]
   (id-spec handle :id))
  ([handle id-field]
      (case (-> @handle :db-spec :subprotocol)
        "sqlite" [id-field :integer :primary :key :autoincrement]
        "postgresql" [id-field :serial :primary :key])))

(defn- next-id-column-name
  "Generates another id-column name if the current one is already used.
   If called with something not starting with :id, an error is thrown."
  [col-name]
  (cond (= col-name :id) :id_
        (= col-name :id_) :id_1
        :else
        (let [[_ nr] (re-find #"_(\d+)$" (str col-name))
              next-nr (inc (Integer/parseInt nr))]
          (keyword (str "id_" next-nr)))))

(defn- id-column-name
  "Determine name of generated id field based on specs in columns.
   Normally this is :id, unless an id-field exists in columns. Then it
  will be id_, or maybe even something else"
  ([columns col-name]
   (let [column-names (map column-name columns)]
     (if (some #{col-name} column-names)
       (recur columns (next-id-column-name col-name))
       col-name)))
  ([columns]
   (id-column-name columns :id)))

;; [2018-07-31 21:36] Do use current connection/transaction here.
;; [2018-08-01 21:02] also add table/columns to connection 'meta' data :new-columns
(defn create-table
  "Create a table
   table: :keyword or string
   columns: vector of: :keyword, string, vector (name, type) or map (:name, :type).
   always include a generated 'id' column. Iff an id-field exists in columns, create an 'id_' field.
   return - void."
  [db-handle table columns]
  (when *DEBUG* (println (format "create-table called with columns: %s" (vec columns))))
  (j/db-do-commands
   (current-connection db-handle)
   [(j/create-table-ddl (dquoted table)
                        (concat [(id-spec db-handle (id-column-name columns))]
                                (mapv column-spec columns)))])
  (when (in-transaction? db-handle)
    (swap! db-handle add-table-columns table columns)))

;; [2018-07-31 21:38] Also use current connection/transaction here.
;; [2018-08-01 21:02] also add table/columns to connection 'meta' data :new-columns
(defn alter-table
  "Alter table, add column(s)
   table - keyword or string
   columns - vector of :keyword, string, vector (name, type) or map (:name, :type)
   Create table iff not exists.
   Return - void."
  [db-handle table columns]
  (if-not (table-exists? db-handle table)
    (create-table db-handle table columns)
    (doseq [column columns]
      (let [[fld-name fld-type] (column-spec column)]
        (j/execute! (current-connection db-handle)
                    [(str "alter table " (dquoted table) " add column "
                          (name fld-name) " " (name fld-type))]))))
  (when (in-transaction? db-handle)
    (swap! db-handle add-table-columns table columns)))

;; 2018-07-29: check if we can use current-connection here as well.
;; => not possible, connection closed messages. 
;; 2018-07-31: this one does not return new tables/columns which have not been
;; committed yet, so manage this as temporary tables/columns within db-handle.
(defn table-meta-data
  "Return seq of table meta data, but for now without column info"
  [db-handle]
  ;; don't use current-connection here, need actual spec.
  (j/with-db-metadata [meta (:db-spec @db-handle)]
    (j/metadata-query (.getTables meta nil nil nil (into-array String ["TABLE"])))))

;; 2018-07-31: also this one does not return new tables/columns which have not been
;; committed yet.
(defn column-meta-data
  "Return seq of column meta data, for table in db"
  [db-handle table]
  ;; don't use current-connection here, need actual spec.
  (j/with-db-metadata [meta (:db-spec @db-handle)]
    (j/metadata-query (.getColumns meta nil nil (name table) nil))))

;; [2018-08-01 21:18] also add tables from :new-columns.
(defn tables1
  "Return sequence of table names (as keywords) in database.
   This one based on DB meta-data, so should be complete.
   But possible issues with locking in transaction."
  [db-handle]
  (oset/into-ordered-set (map (comp keyword :table_name) (table-meta-data db-handle))))

(defn tables2
  "Return sequence of table names (as keywords) in database.
   This one checks our own meta-data of just added tables and fields.
   Should be independent of DB transactions."
  [db-handle]
  (oset/into-ordered-set (keys (:new-columns @db-handle))))

(defn tables
  "Return sequence of table names (as keywords) in database."
  [db-handle]
  (set/union (tables1 db-handle) (tables2 db-handle)))

;; 2020-05-21: this should be easier with a set as result from tables.
;; not sure if (boolean) is needed, does not look idiomatic.
;; 2022-12-08: check here is case-sensitive. This can give an issue in
;; SQLite, where table names are case-insensitive. E.g. when first
;; checking if a table exists, and if not, creating the table.
;; maybe keep the list of tables within db-handle in lowercase.
(defn table-exists?
  "Given db-handle and table, returns true iff table exists within DB, false otherwise.
   Table can be keyword or string.
   First check own meta-data (tables2), then DB meta-data (tables1)"
  [db-handle table]
  (boolean (or (get (tables2 db-handle) (keyword table))
               (get (tables1 db-handle) (keyword table)))))

;; version specific for SQLite.
;; TODO - possibly create separate namespaces for postgres and sqlite, and move there.
#_(defn table-exists? [db-handle table]
    (some? (j/query (:db-spec @db-handle)
                    ["select 1 FROM sqlite_master WHERE type='table' AND name=?"
                     table])))

(defn columns1
  "Return set of column names (as keywords) in table in database
   table can be a string or keyword.
   Look in both DB metadata (with query) as well as newly added tables/columns (in cache)
   This one based on DB meta-data, so should be complete.
   But possible issues with locking in transaction."
  [db-handle table]
  (oset/into-ordered-set (map (comp keyword :column_name) (column-meta-data db-handle (name table)))))

(defn columns2
  "Return set of column names (as keywords) in table in database
   table can be a string or keyword.
   Look in both DB metadata (with query) as well as newly added tables/columns (in cache)
   This one checks our own meta-data of just added tables and fields.
   Should be independent of DB transactions."
  [db-handle table]
  (get-in @db-handle [:new-columns (keyword table)]))

(defn columns
  "Return set of column names (as keywords) in table in database
   table can be a string or keyword.
   Look in both DB metadata (with query) as well as newly added tables/columns (in cache)"
  [db-handle table]
  (set/union (columns1 db-handle table)
             (columns2 db-handle table)))

;; 2020-05-21: should be easier with columns as a set. Do use
;; (boolean), according to spec, only return true or false
(defn column-exists?
  "Return true iff columns exists in table within db.
   table - keyword or string
   column - keyword or string"
  [db-handle table column]
  (boolean (or (get (columns2 db-handle (name table)) (keyword column))
               (get (columns1 db-handle (name table)) (keyword column)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DML functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO - maybe this should be in a protocol.
;; 2023-09-23: In [org.xerial/sqlite-jdbc "3.43.0.0"] we get just a
;; sequence with the ID, no keywords, so add (first row) as a try.
;; 2023-11-30: No, not a sequence, just a 1, even if the new id is something else.
;; need some construction with 'returning'.
(defn get-first-id
  "Return created id of first row of result-set.
   This is different for SQLite and Postgres"
  [_db-handle res]
  ;; (println "get-first-id, res: " res)
  (let [row (first res)]
    ;; (println "  row: " row)
    (or (:id row)
        (get row (keyword "last_insert_rowid()"))
        (first row))))

;; from https://clojuredocs.org/clojure.core/reduce-kv
(defn map-kv
  "Apply f to very val in coll, return result"
  [f coll]
  (reduce-kv (fn [m k v] (assoc m k (f v))) (empty coll) coll))

;; from https://clojuredocs.org/clojure.core/reduce-kv, changed a bit.
(defn map-kv-key
  "Apply f to every key in coll, return result"
  [f coll]
  (reduce-kv (fn [m k v] (assoc m (f k) v)) (empty coll) coll))

;; reserved words sqlite: https://www.sqlite.org/lang_keywords.html
;; for postgres: to check.
(defn dquoted-record
  "Convert keys in record/map to string and surround with double quotes.
   Wrt using reserved words in the table.
   Maybe only do this for the list of 145 (SQlite) reserved words"
  [record]
  (map-kv-key dquoted record))

(defn get-latest-id
  "Return latest generated id for a table"
  [db-handle table]
  (let [query (format "select max(id) max_id from %s" (dquoted (name table)))
        res (j/query (current-connection db-handle) query)]
    #_(println "res of get-latest:" res)
    (-> res first :max_id)))

;; 2020-05-22: also use dquoted for table
;; 2023-11-30: new version using 'returning' clause, should work in
;; both SQLite and Postgres
(defn insert-no-check
  "Insert record into table, no checks if table/columns exist.
   Return generated id."
  [db-handle table record]
  (j/insert! (current-connection db-handle)
             (dquoted (name table)) (dquoted-record record))
  (get-latest-id db-handle table))

;; 2025-10-29: another old version, _res is not used.
#_(defn insert-no-check
    "Insert record into table, no checks if table/columns exist.
   Return generated id."
    [db-handle table record]
    (let [_res (j/insert! (current-connection db-handle)
                          (dquoted (name table)) (dquoted-record record))
          latest-id (get-latest-id db-handle table)]
      latest-id))

;; 2023-12-02: old version, with get-first-id, keep a bit longer.
#_(defn insert-no-check
    "Insert record into table, no checks if table/columns exist.
   Return generated id."
    [db-handle table record]
    (let [res (j/insert! (current-connection db-handle)
                         (dquoted (name table)) (dquoted-record record))]
      ;; (println "insert-no-check: res:" res)
      (get-first-id db-handle res)))

;; TODO create a nested map here?
;; or use a multi method? DB specific things in separate namespace, and ask functions there
;; this does not look idiomatic!
;; 2020-05-19: set :varchar for nil-values.
(def sql-db-types
  "SQL types for combinations of database and Clojure/Java values
   Includes defaults per datatype and also per database type."
  {java.lang.Integer :integer
   java.lang.Long :integer
   java.lang.Double :float
   java.lang.Byte :integer
   java.lang.String :varchar
   nil :varchar
   ;; [2019-03-17 13:26] remove defaults per database, want to get an Exception on an unknown class.
   ;;   "sqlite" :varchar
   ;;   "postgresql" :varchar
   ["postgresql" java.sql.Date] :date
   ["postgresql" java.sql.Timestamp] [:timestamp "(3)" :with :time :zone]
   ["postgresql" java.time.LocalDate] :date
   ["postgresql" java.time.LocalDateTime] [:timestamp "(3)" :with :time :zone]
   ["postgresql" java.time.OffsetDateTime] [:timestamp "(3)" :with :time :zone]
   ["postgresql" java.time.Instant] [:timestamp "(3)" :with :time :zone]
   ["sqlite" java.sql.Date] :date
   ["sqlite" java.sql.Timestamp] :timestamp
   ["sqlite" java.time.LocalDate] :date
   ["sqlite" java.time.LocalDateTime] :timestamp
   ["sqlite" java.time.OffsetDateTime] :timestamp
   ["sqlite" java.time.Instant] :timestamp
   })

(defn sql-db-type
  "Determine sql type of a value
   For now only integer, float, string and date-time types for Postgres.
   Default to varchar if no match.
   sql-type dependent on db-spec, eg with date/time values.
   throws an exception if type cannot be determined.
   (maybe should return varchar in that case)
   2022-03-27: now return varchar"
  [db-spec value]
  (let [db-type (:subprotocol db-spec)
        cls (class value)]
    (or (get sql-db-types [db-type cls])
        (get sql-db-types cls)
        (get sql-db-types db-type)
        :varchar
        (throw (Exception. (str "Could not determine sql-type for "
                                (:subprotocol db-spec) " and " value " (" cls ")"))))))

(def db-class-map
  "Mapping of java-class to conversion function wrt sql types
   JSR310 LocalDate and LocalDateTime are supported, no need for conversion."
  {
   ;; Instants are saved as UTC. Needs to be converted via offset-date-time, is tricky.
   ;; In test both query result from Clojure en directly in Postgres give correct results
   ;; with this one. Via local-date-time does not work correctly.
   ;; should use the JSR 310 classes directly in jdbc/postgres.
   java.time.Instant #(time/offset-date-time %1 (time/zone-offset 0))
   })

(defn db-value
  "Convert a value to a type understood by jdbc/sql.
   Can also be some JSR 310 types"
  [val]
  (if-let [f (get db-class-map (class val))]
    (f val)
    val))

(defn db-values
  "Convert values in record/map to SQL types"
  [record]
  (map-kv db-value record))

(defn column-with-datatype
  "Determine SQL datatype of column within record.
   Return seq of columnname and SQL-datatype.
   datatype could be more than one :keyword"
  [db-spec record column-name]
  (let [col-tp (sql-db-type db-spec (get record (keyword column-name)))]
    (cond (keyword? col-tp) [column-name col-tp]
          (vector? col-tp) (into [] (concat [column-name] col-tp)))))

;; 2025-10-29: From https://cljdoc.org/d/org.flatland/ordered/1.15.12/api/flatland.ordered.set#ordered-set:
;; Note that clojure.set functions like union, intersection, and
;; difference can change the order of their input sets for efficiency
;; purposes, so may not return the order you expect given ordered sets
;; as input.
;; for now, expect mostly ok, iff rows added (e.g. from Excel) all have the same columns.

(defn new-columns
  "Determine new columns (including types) for table based on record.
   cols - the current columns of table (seq of keywords)
   record - the hashmap with needed columns of table (columns als keywords)
   return - seq of new columns"
  [db-spec cols record]
  (when (nil? db-spec) (throw (Exception. "db-spec is nil")))
  (let [names (vec (set/difference (oset/into-ordered-set (keys record)) (oset/into-ordered-set cols)))]
    (map #(column-with-datatype db-spec record %) names)))

(defn new-columns2
  "Determine new columns (including types) for table based on record.
   cols - the current columns of table (seq of keywords)
   record - the hashmap with needed columns of table (columns als keywords)
   return - seq of new columns.
   If based on own meta-data we see no new columns, we can already return with an empty result;
   if we see new columns, we also need to check DB meta-data."
  [db-spec db-handle table cols2 record]
  (when (nil? db-spec) (throw (Exception. "db-spec is nil")))
  (let [names (vec (set/difference (oset/into-ordered-set (keys record)) (oset/into-ordered-set cols2)))]
    (if (seq names)
      (let [cols1 (columns1 db-handle table)
            names2 (vec (set/difference (oset/into-ordered-set (keys record)) (oset/into-ordered-set cols1) (oset/into-ordered-set cols2)))]
        ;; also check db meta-data
        (map #(column-with-datatype db-spec record %) names2))
      [])))

;; TODO - maybe refactor, cols1 and cols2 are determined in different places now.
(defn- add-columns-for-record
  "Create or alter table so record can be inserted.
   If based on own meta-data we see no new columns, we can already return with an empty result;
   if we see new columns, we also need to check DB meta-data."
  [db-handle table record]
  (let [db-spec (:db-spec @db-handle)
        cols2 (columns2 db-handle table)]
    (if (table-exists? db-handle table)
      (alter-table db-handle table
                   (new-columns2 db-spec db-handle table cols2 record))
      (create-table db-handle table (new-columns db-spec [] record)))))

#_(defn- add-columns-for-record
    "Create or alter table so record can be inserted"
    [db-handle table record]
    (let [db-spec (:db-spec @db-handle)]
      (if (table-exists? db-handle table)
        (alter-table db-handle table
                     (new-columns db-spec
                                  (columns db-handle table) record))
        (create-table db-handle table (new-columns db-spec [] record)))))

(defn omap-update-keys
  "m f => {(f k) v ...}

  Given a map m and a function f of 1-argument, returns a new map whose
  keys are the result of applying f to the keys of m, mapped to the
  corresponding values of m.
  f must return a unique key for each key of m, else the behavior is undefined.
  ordered-map version"
  {:added "1.11"}
  [m f]
  (let [ret (persistent!
             (reduce-kv (fn [acc k v] (assoc! acc (f k) v))
                        (transient (omap/ordered-map))
                        m))]
    (with-meta ret (meta m))))

;; TODO - use map-kv-keys, see above.
;; (clojure.string/replace "abc.def" #"[^A-Za-z0-9_]" "_")
;; could use update-keys here?
;; [2024-03-30 10:26] Are keys keywords or strings? Or can be both? -> both indeed.
;; [2024-03-30 10:30] Maybe sanitising should be optional. But this is an opinionated library.
;; [2024-03-30 10:49] for now not private, so can be checked from other scripts/libs.
(defn sanitise-record
  "Sanitise column/key names in record. eg replace - by _
   Replace every non-letter-digit with an underscore."
  [record]
  (letfn [(replace-dash [s] (str/replace s #"[^A-Za-z0-9_]" "_"))
          (sanitise-key [k] (-> k name replace-dash keyword))]
    (omap-update-keys record sanitise-key)))

#_(defn- sanitise-record2
    "Sanitise column/key names in record. eg replace - by _
   Replace every non-letter-digit with an underscore."
    [record]
    (letfn [(replace-dash [s] (clojure.string/replace s #"[^A-Za-z0-9_]" "_"))
            (sanitise-key [k] (-> k name replace-dash keyword))]
      (reduce-kv (fn [m k v]
                   (assoc m (sanitise-key k) v)) {} record)))

;; TODO: check if add-columns-for-record needs to be more efficient, or some checks within
;; clojure code, not needing DB meta data.
;; [2019-03-10 19:51] this function is now the most complete, also the slowest.
(defn insert
  "Insert record into table, adding table/columns if needed.
   Default/pessimistic version: first check if table and columns exist and create/alter
   table where needed; then insert record.
   Also call db-values on record."
  [db-handle table record]
  (when *DEBUG* (println (format "insert called with record: %s" record)))
  (let [record-san (-> record sanitise-record db-values)]
    (when *DEBUG* (println (format "record-san: %s" record-san)))
    (add-columns-for-record db-handle table record-san)
    (insert-no-check db-handle table record-san)))

;; TODO: check was on catch org.sqlite.SQLiteException e
;; but also needs to work with Postgres: org.postgresql.util.PSQLException
;; check on generic Exception for now.
(defn insert-opt
  "Insert record into table, adding table/columns if needed.
   Optimistic version: first try to insert; if fails, add table/column(s) and try again.
   This version might be faster, need some tests."
  [db-handle table record]
  (let [record-san (sanitise-record record)]
    (try
      (when *DEBUG* (println "DEBUG: Before first try: " record))
      ;; if not error, result of insert-no-check will be returned.
      (insert-no-check db-handle table record-san)
      (catch Exception e
        (do
          (when *DEBUG* (println "DEBUG: Caught exception: " e))
          (add-columns-for-record db-handle table record-san)
          (when *DEBUG* (println "DEBUG: Added columns, now retry"))
          ;; retry, but not with recursive call.
          (let [id (insert-no-check db-handle table record-san)]
            (when *DEBUG* (println "DEBUG: Retry finished"))
            id))))))

(defn query
  "Perform a SQL (select) query, return sequence of maps.
   Also handle optional params-sequence given."
  ([db-handle sql]
   (query db-handle sql []))
  ([db-handle sql params]
   (j/query (current-connection db-handle) (cons sql params))))

(defn exec
  "Execute a DDL/DML query.
   Also handle optional params-sequence given."
  ([db-handle sql]
   (exec db-handle sql []))
  ([db-handle sql params]
   (j/execute! (current-connection db-handle) (cons sql params))))

(comment
  ;; create testcase for order of columns: either the same as in Excel, or maybe alphabetical.
  (def db (open-db-sqlite "/tmp/testdb.db"))
  (def row1 {:a 1 :b 2 :c 3 :d 4 :e 5})
  (def row2 (omap/ordered-map :a 11 :b 12 :c 13 :d 14 :e 15))
  (insert db :test1 row1)
  (insert db :test1 row2)
  (close-db db)
  (println "Test from comment")
  (version)

  (do
    (def dbpath "/tmp/testdb.db")
    (fs/delete dbpath)
    (def db (open-db-sqlite dbpath))
    (def row2 (omap/ordered-map :a 11 :b 12 :c 13 :d 14 :e 15))
    (insert db :test1 row2)
    (close-db db)
    (println "Test from comment"))

  ;; in new-columns deze code:
  #_(let [names (vec (set/difference (oset/into-ordered-set (keys record)) (oset/into-ordered-set cols)))]
      (map #(column-with-datatype db-spec record %) names))

  (set/difference (set (keys row2)) (set #{})) ;; -> returns e c b d a, in that order.
  row2
  (keys row2) ;; a..e in right order, as a seq.
  (set (keys row2)) ;; wrong order.
  ;; [clojure.set :as set]
  ;; try ordered-set here.
  (apply oset/ordered-set (keys row2)) ;; ok, this works, but is there something without needing apply?
  (oset/into-ordered-set (keys row2)) ;; ok, this works, so better.
  (set/difference (oset/into-ordered-set (keys row2)) (set #{})) ;; -> returns a..e, so ok.

  )
