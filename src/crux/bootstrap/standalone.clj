(ns crux.bootstrap.standalone
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [crux.backup :as backup]
            [crux.bootstrap :as b]
            [crux.codec :as c]
            [crux.db :as db]
            [crux.io :as cio]
            [crux.index :as idx]
            [crux.kv :as kv]
            [crux.lru :as lru]
            [crux.query :as q]
            [crux.tx :as tx])
  (:import java.io.Closeable
           crux.api.ICruxAPI
           crux.bootstrap.CruxNode))

(defrecord StandaloneSystem [kv-store event-log-kv-store event-log-consumer tx-log options]
  ICruxAPI
  (db [this]
    (.db ^CruxNode (b/map->CruxNode this)))

  (db [this valid-time]
    (.db ^CruxNode (b/map->CruxNode this) valid-time))

  (db [this valid-time transact-time]
    (.db ^CruxNode (b/map->CruxNode this) valid-time transact-time))

  (document [this content-hash]
    (.document ^CruxNode (b/map->CruxNode this) content-hash))

  (history [this eid]
    (.history ^CruxNode (b/map->CruxNode this) eid))

  (historyRange [this eid valid-time-start transaction-time-start valid-time-end transaction-time-end]
    (.historyRange ^CruxNode (b/map->CruxNode this) eid valid-time-start transaction-time-start valid-time-end transaction-time-end))

  (attributeStats [this]
    (idx/read-meta kv-store :crux.kv/stats))

  (status [this]
    (-> (.status ^CruxNode (dissoc (b/map->CruxNode this)
                                   :event-log-kv-store))
        (assoc :crux.zk/zk-active? false)))

  (submitTx [this tx-ops]
    (.submitTx ^CruxNode (b/map->CruxNode this) tx-ops))

  (hasSubmittedTxUpdatedEntity [this submitted-tx eid]
    (.hasSubmittedTxUpdatedEntity ^CruxNode (b/map->CruxNode this) submitted-tx eid))

  (hasSubmittedTxCorrectedEntity [this submitted-tx valid-time eid]
    (.hasSubmittedTxCorrectedEntity ^CruxNode (b/map->CruxNode this) submitted-tx valid-time eid))

  (newTxLogContext [this]
    (.newTxLogContext ^CruxNode (b/map->CruxNode this)))

  (txLog [this tx-log-context from-tx-id with-documents?]
    (.txLog ^CruxNode (b/map->CruxNode this) tx-log-context from-tx-id with-documents?))

  (sync [this timeout]
    (.sync ^CruxNode (b/map->CruxNode this) timeout))

  (sync [this transaction-time timeout]
    (.sync ^CruxNode (b/map->CruxNode this) transaction-time timeout))

  backup/ISystemBackup
  (write-checkpoint [this {:keys [crux.backup/checkpoint-directory]}]
    (kv/backup kv-store (io/file checkpoint-directory "kv-store"))
    (when event-log-kv-store
      (kv/backup event-log-kv-store (io/file checkpoint-directory "event-log-kv-store"))))

  Closeable
  (close [_]
    (doseq [c [event-log-consumer tx-log kv-store]]
      (cio/try-close c))))

(s/def ::standalone-options (s/keys :req-un [:crux.kv/db-dir :crux.tx/event-log-dir :crux.kv/kv-backend]
                                    :opt-un [:crux.kv/sync? :crux.db/object-store :crux.lru/doc-cache-size]
                                    :opt [:crux.tx/event-log-sync-interval-ms
                                          :crux.tx/event-log-kv-backend]))

(defn start-standalone-system ^ICruxAPI [options]
  (s/assert ::standalone-options options)
  (let [{:keys [db-dir sync? kv-backend event-log-dir doc-cache-size
                crux.tx/event-log-kv-backend crux.tx/event-log-sync-interval-ms] :as options}
        (merge b/default-options options)
        started (atom [])]
    (try
      (let [kv-store (doto (b/start-kv-store
                             (merge (when-not event-log-dir
                                      {:sync? true})
                                    options))
                       (->> (swap! started conj)))
            event-log-sync? (boolean (or sync? (not event-log-sync-interval-ms)))
            event-log-kv-store (doto (b/start-kv-store
                                      {:db-dir event-log-dir
                                       :kv-backend (or event-log-kv-backend kv-backend)
                                       :sync? event-log-sync?
                                       :crux.index/check-and-store-index-version false})
                                 (->> (swap! started conj)))
            object-store (lru/->CachedObjectStore (lru/new-cache doc-cache-size)
                                                  (b/start-object-store {:kv kv-store} options))

            tx-log (doto (tx/->EventTxLog event-log-kv-store)
                     (->> (swap! started conj)))

            indexer (tx/->KvIndexer kv-store tx-log object-store)

            event-log-consumer (when event-log-kv-store
                                 (doto (tx/start-event-log-consumer event-log-kv-store indexer (when-not sync?
                                                                                                 event-log-sync-interval-ms))
                                   (->> (swap! started conj))))]

        (map->StandaloneSystem {:kv-store kv-store
                                :event-log-kv-store event-log-kv-store
                                :tx-log tx-log
                                :object-store object-store
                                :indexer indexer
                                :event-log-consumer event-log-consumer
                                :options options}))
      (catch Throwable t
        (doseq [c (reverse @started)]
          (cio/try-close c))
        (throw t)))))
