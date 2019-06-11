(ns crux.fixtures
  (:require [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [clojure.test.check.clojure-test :as tcct]
            [clojure.java.io :as io]
            [crux.bootstrap :as b]
            [crux.codec :as c]
            [crux.db :as db]
            [crux.http-server :as srv]
            [crux.io :as cio]
            [crux.kafka.embedded :as ek]
            [crux.kafka :as k]
            [crux.kv :as kv]
            [crux.tx :as tx]
            [crux.index :as idx]
            [crux.lru :as lru]
            [taoensso.nippy :as nippy]
            [crux.memory :as mem])
  (:import java.io.Closeable
           [java.util Properties UUID]
           org.apache.kafka.clients.admin.AdminClient
           org.apache.kafka.clients.producer.KafkaProducer
           org.apache.kafka.clients.consumer.KafkaConsumer
           [crux.api Crux ICruxAPI]))

(defn random-person [] {:crux.db/id (UUID/randomUUID)
                        :name      (rand-nth ["Ivan" "Petr" "Sergei" "Oleg" "Yuri" "Dmitry" "Fedor" "Denis"])
                        :last-name (rand-nth ["Ivanov" "Petrov" "Sidorov" "Kovalev" "Kuznetsov" "Voronoi"])
                        :sex       (rand-nth [:male :female])
                        :age       (rand-int 100)
                        :salary    (rand-int 100000)})

(defn maps->tx-ops
  ([maps]
    (vec (for [m maps]
            [:crux.tx/put (:crux.db/id m) m])))
  ([maps ts]
   (vec (for [m maps]
          [:crux.tx/put
           (:crux.db/id m)
           m
           ts]))))

(def ^:dynamic *kv*)
(def ^:dynamic *kv-backend* "crux.kv.rocksdb.RocksKv")
(def ^:dynamic *check-and-store-index-version* true)

(defn kv-object-store-w-cache [kv]
  (lru/->CachedObjectStore
    (lru/new-cache (:doc-cache-size b/default-options))
    (b/start-object-store {:kv kv} b/default-options)))

(defn without-kv-index-version [f]
  (binding [*check-and-store-index-version* false]
    (f)))

(defrecord KvTxLog [kv object-store]
  db/TxLog
  (submit-doc [this content-hash doc]
    (db/index-doc (tx/->KvIndexer kv this object-store) content-hash doc))

  (submit-tx [this tx-ops]
    (s/assert :crux.api/tx-ops tx-ops)
    (let [transact-time (cio/next-monotonic-date)
          tx-id (.getTime transact-time)
          tx-events (tx/tx-ops->tx-events tx-ops)
          indexer (tx/->KvIndexer kv this object-store)]
      (kv/store kv [[(c/encode-tx-log-key-to nil tx-id transact-time)
                     (nippy/fast-freeze tx-events)]])
      (doseq [doc (tx/tx-ops->docs tx-ops)]
        (db/submit-doc this (str (c/new-id doc)) doc))
      (db/index-tx indexer tx-events transact-time tx-id)
      (db/store-index-meta indexer
                           :crux.tx-log/consumer-state
                           {:crux.kv.topic-partition/tx-log-0
                            {:lag 0
                             :time transact-time}})
      (delay {:crux.tx/tx-id tx-id
              :crux.tx/tx-time transact-time})))

  (new-tx-log-context [this]
    (kv/new-snapshot kv))

  (tx-log [this tx-log-context from-tx-id]
    (let [i (kv/new-iterator tx-log-context)]
      (for [[k v] (idx/all-keys-in-prefix i (c/encode-tx-log-key-to nil from-tx-id) (c/encode-tx-log-key-to nil) true)]
        (assoc (c/decode-tx-log-key-from k)
               :crux.api/tx-ops (nippy/fast-thaw (mem/->on-heap v))))))

  Closeable
  (close [_]))

(defn create-kv-tx-log
  [kv object-store]
  (let [tx-log (->KvTxLog kv object-store)
        indexer (tx/->KvIndexer kv tx-log object-store)]
    (when-not (db/read-index-meta indexer :crux.tx-log/consumer-state)
      (db/store-index-meta
       indexer
       :crux.tx-log/consumer-state
       {:crux.kv.topic-partition/tx-log-0
        {:lag 0 :time nil}}))
    tx-log))

(defn kv-tx-log-w-cache [kv]
  (create-kv-tx-log kv (kv-object-store-w-cache kv)))

(defn with-kv-store [f]
  (let [db-dir (cio/create-tmpdir "kv-store")]
    (try
      (binding [*kv* (b/start-kv-store {:db-dir (str db-dir)
                                        :kv-backend *kv-backend*
                                        :crux.index/check-and-store-index-version *check-and-store-index-version*})]
        (with-open [*kv* ^Closeable *kv*]
          (f)))
      (finally
        (cio/delete-dir db-dir)))))

(defn with-memdb [f]
  (binding [*kv-backend* "crux.kv.memdb.MemKv"]
    (t/testing "MemDB"
      (f))))

(defn with-rocksdb [f]
  (binding [*kv-backend* "crux.kv.rocksdb.RocksKv"]
    (t/testing "RocksDB"
      (f))))

(defn with-rocksdb-jnr [f]
  (binding [*kv-backend* "crux.kv.rocksdb.jnr.RocksJNRKv"]
    (t/testing "RocksJNRDB"
      (f))))

(defn with-lmdb [f]
  (binding [*kv-backend* "crux.kv.lmdb.LMDBKv"]
    (t/testing "LMDB"
      (f))))

(defn with-lmdb-jnr [f]
  (binding [*kv-backend* "crux.kv.lmdb.jnr.LMDBJNRKv"]
    (t/testing "LMDBJNR"
      (f))))

(defn with-each-kv-store-implementation [f]
  (doseq [with-kv-store-implementation [with-memdb with-rocksdb with-rocksdb-jnr with-lmdb with-lmdb-jnr]]
    (with-kv-store-implementation f)))

(def ^:dynamic *kafka-bootstrap-servers*)

(defn write-kafka-meta-properties [log-dir broker-id]
  (let [meta-properties (io/file log-dir "meta.properties")]
    (when-not (.exists meta-properties)
      (io/make-parents meta-properties)
      (with-open [out (io/output-stream meta-properties)]
        (doto (Properties.)
          (.setProperty "version" "0")
          (.setProperty "broker.id" (str broker-id))
          (.store out ""))))))

(def ^:dynamic ^AdminClient *admin-client*)

(defn with-embedded-kafka-cluster [f]
  (let [zookeeper-data-dir (cio/create-tmpdir "zookeeper")
        zookeeper-port (cio/free-port)
        kafka-log-dir (doto (cio/create-tmpdir "kafka-log")
                        (write-kafka-meta-properties ek/*broker-id*))
        kafka-port (cio/free-port)]
    (try
      (with-open [embedded-kafka (ek/start-embedded-kafka
                                  #:crux.kafka.embedded{:zookeeper-data-dir (str zookeeper-data-dir)
                                                        :zookeeper-port zookeeper-port
                                                        :kafka-log-dir (str kafka-log-dir)
                                                        :kafka-port kafka-port})
                  admin-client (k/create-admin-client
                                {"bootstrap.servers" (get-in embedded-kafka [:options :bootstrap-servers])})]
        (binding [*admin-client* admin-client
                  *kafka-bootstrap-servers* (get-in embedded-kafka [:options :bootstrap-servers])]
          (f)))
      (finally
        (cio/delete-dir kafka-log-dir)
        (cio/delete-dir zookeeper-data-dir)))))

(def ^:dynamic ^KafkaProducer *producer*)
(def ^:dynamic ^KafkaConsumer *consumer*)

(def ^:dynamic *consumer-options* {})

(defn with-kafka-client [f & {:keys [consumer-options]}]
  (with-open [producer (k/create-producer {"bootstrap.servers" *kafka-bootstrap-servers*})
              consumer (k/create-consumer
                         (merge {"bootstrap.servers" *kafka-bootstrap-servers*
                                 "group.id" (str (UUID/randomUUID))}
                                *consumer-options*))]
    (binding [*producer* producer
              *consumer* consumer]
      (f))))

(def ^:dynamic *api-url*)
(def ^:dynamic ^ICruxAPI *api*)
(def ^:dynamic ^ICruxAPI *cluster-node*)

(defn with-cluster-node [f]
  (assert (bound? #'*kafka-bootstrap-servers*))
  (assert (not (bound? #'*kv*)))
  (let [server-port (cio/free-port)
        db-dir (str (cio/create-tmpdir "kv-store"))
        test-id (UUID/randomUUID)
        tx-topic (str "tx-topic-" test-id)
        doc-topic (str "doc-topic-" test-id)
        options {:server-port server-port
                 :db-dir db-dir
                 :tx-topic tx-topic
                 :doc-topic doc-topic
                 :kv-backend *kv-backend*
                 :bootstrap-servers *kafka-bootstrap-servers*}]
    (try
      (with-open [cluster-node (Crux/startClusterNode options)
                  http-server (srv/start-http-server cluster-node options)]
        (binding [*cluster-node* cluster-node
                  *api* cluster-node
                  *api-url* (str "http://" ek/*host* ":" server-port)]
          (f)))
      (finally
        (cio/delete-dir db-dir)))))

(defn with-standalone-system [f]
  (assert (not (bound? #'*kv*)))
  (let [db-dir (str (cio/create-tmpdir "kv-store"))
        event-log-dir (str (cio/create-tmpdir "event-log-dir"))
        options {:db-dir db-dir
                 :event-log-dir event-log-dir
                 :kv-backend *kv-backend*}]
    (try
      (with-open [standalone-system (Crux/startStandaloneSystem options)]
        (binding [*api* standalone-system]
          (f)))
      (finally
        (cio/delete-dir db-dir)
        (cio/delete-dir event-log-dir)))))

(defn with-api-client [f]
  (assert (bound? #'*api-url*))
  (with-open [api-client (Crux/newApiClient *api-url*)]
    (binding [*api* api-client]
      (f))))

(defn with-each-api-implementation [f]
  (t/testing "Local API ClusterNode"
    (with-cluster-node f))
  (t/testing "Local API StandaloneSystem"
    (with-standalone-system f))
  (t/testing "Remote API"
    (with-cluster-node
      #(with-api-client f))))

(defn with-silent-test-check [f]
  (binding [tcct/*report-completion* false]
    (f)))

(defn kv-tx-log
  ([kv] (create-kv-tx-log kv (idx/->KvObjectStore kv)))
  ([kv object-store] (create-kv-tx-log kv object-store)))

(defn transact-entity-maps!
  ([kv entities]
   (transact-entity-maps! kv entities (cio/next-monotonic-date)))
  ([kv entities ts]
   (let [tx-log (kv-tx-log kv)
         tx-ops (maps->tx-ops entities ts)]
     @(db/submit-tx tx-log tx-ops)
     entities)))

(defn entities->delete-tx-ops [entities ts]
  (vec (for [e entities]
         [:crux.tx/delete e ts])))

(defn delete-entities!
  ([kv entities]
   (delete-entities! kv entities (cio/next-monotonic-date)))
  ([kv entities ts]
   (let [tx-log (kv-tx-log kv)
         tx-ops (entities->delete-tx-ops entities ts)]
     @(db/submit-tx tx-log tx-ops)
     entities)))

(defn transact-people!
  ([kv people-mixins]
   (transact-people! kv people-mixins (cio/next-monotonic-date)))
  ([kv people-mixins ts]
   (transact-entity-maps! kv (->> people-mixins (map merge (repeatedly random-person))) ts)))
