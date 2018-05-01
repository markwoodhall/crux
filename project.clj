(defproject crux "0.1.0-SNAPSHOT"
  :description "A Clojure library that gives graph query over a K/V store such as RocksDB"
  :url "https://github.com/juxt/crux"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.taoensso/nippy "2.13.0"]
                 [org.rocksdb/rocksdbjni "5.11.3"]
                 [gloss "0.2.6"]]
  :profiles {:dev {:dependencies [[clj-time "0.14.2"]]
                   :source-paths ["bench"]}}
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort
  :jvm-opts ~(vec (remove nil?
                          [(when (System/getenv "USE_YOURKIT_AGENT")
                             (when-let [path (first (filter #(.exists (clojure.java.io/as-file %))
                                                            ["/home/jon/dev/yourkit/bin/linux-x86-64/libyjpagent.so"]))]
                               (str "-agentpath:" path)))])))
