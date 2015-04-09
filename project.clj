(defproject storm.metric/opentsdb-metrics-consumer "0.0.2"
  :description "Storm Metrics OpenTSDB Metrics Consumer"
  :dependencies [[org.clojure/clojure "1.5.1"]]
  :min-lein-version "2.0.0"
  :aot :all
  :profiles {:provided
              {:dependencies [
                               ;; the storm server only provides the storm-core.
                               ;; so storm-core jar should be excluded when building uberjar
                               ;; other storm-related jars like storm-kafka should be compiled-in
                               ;; see: http://mail-archives.apache.org/mod_mbox/storm-user/201407.mbox/%3CCAN4Gn11592SWxy8EvY3eaLvo_ERujA6w0EX0OWpZg4FwjaWdrg@mail.gmail.com%3E
                               [org.apache.storm/storm-core "0.9.3"]]}})
