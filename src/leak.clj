(ns leak
  "Native memory leak in Datalevin 1.2.2: closing a cursor that scanned an
  entity stored in an LMDB sub-database never frees the sub-cursor's list
  duplicate buffer. Run: clojure -M:run [cycles]"
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [datalevin.core :as d]
            [datalevin.constants :as c]
            [datalevin.interface :as i]
            [datalevin.lmdb :as l]
            [datalevin.scan :as scan])
  (:import [java.nio.file Files]))

(defn rss-mb []
  (let [pid (.pid (java.lang.ProcessHandle/current))
        out (:out (sh/sh "ps" "-o" "rss=" "-p" (str pid)))]
    (quot (Long/parseLong (str/trim out)) 1024)))

(defn scan-and-close!
  "What Datalevin does for every scan in a write transaction or on a virtual
  thread: open a cursor, read the entity's datom list, close the cursor."
  [lmdb eid]
  (let [dbi (i/get-dbi lmdb c/eav false)
        rtx (i/get-rtx lmdb)
        cur (l/get-cursor dbi rtx)
        it  (l/val-iterator (l/iterate-list-val-full dbi rtx cur))]
    (try (count (scan/get-list* lmdb it eid :id :avg))
         (finally (.close ^java.lang.AutoCloseable it)
                  (l/close-cursor dbi cur)      ; mdb_cursor_close: leaks ~440 KB
                  (i/return-rtx lmdb rtx)))))

(defn -main [& [cycles]]
  (let [cycles (Long/parseLong (or cycles "1000"))
        dir (str (Files/createTempDirectory "datalevin-leak" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (d/get-conn dir {:id {:db/valueType :db.type/string :db/unique :db.unique/identity}
                              :tags {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}})]
    ;; 900 values of ~490 bytes: the entity's datoms no longer fit a leaf page,
    ;; so LMDB keeps them in a sub-database and the list scan copies them into
    ;; a ~440 KB buffer on the cursor's sub-cursor (mc_xcursor).
    (d/transact! conn [{:id "wide" :tags (mapv #(str "tag-" % "-" (apply str (repeat 470 "x"))) (range 900))}])
    (let [db (d/db conn)
          eid (:db/id (d/entity db [:id "wide"]))
          lmdb (.-lmdb (.-store db))]
      (println "datoms:" (scan-and-close! lmdb eid))
      (println "RSS before:" (rss-mb) "MB")
      (dotimes [n cycles]
        (scan-and-close! lmdb eid)
        (when (zero? (mod (inc n) (quot cycles 5)))
          (println (format "after %5d scan+close cycles: RSS %5d MB" (inc n) (rss-mb)))))
      (System/gc)
      (println "RSS after GC:" (rss-mb) "MB  (expected if fixed: roughly the 'before' value)"))
    (d/close conn)
    (shutdown-agents)
    (System/exit 0)))
