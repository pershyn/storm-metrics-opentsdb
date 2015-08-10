(defproject storm.metric/opentsdb-metrics-consumer "0.0.5"
  :description "Storm Metrics OpenTSDB Metrics Consumer"
  :dependencies [[org.clojure/clojure "1.5.1"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :aot :all
  :main storm.metric.OpenTSDBMetricsConsumer
  :profiles {:provided
              {:dependencies [
                              ;; the storm server only provides the storm-core.
                              ;; so storm-core jar should be excluded when building uberjar
                              ;; other storm-related jars like storm-kafka should be compiled-in
                              ;; see: http://mail-archives.apache.org/mod_mbox/storm-user/201407.mbox/%3CCAN4Gn11592SWxy8EvY3eaLvo_ERujA6w0EX0OWpZg4FwjaWdrg@mail.gmail.com%3E
                              ;; there are also some other jars that are provided. They are in normally in `lib` folder of storm distribution
                              ;; they are also mentioned in pom: https://github.com/apache/storm/blob/v0.9.5/pom.xml#L293
                              [org.apache.storm/storm-core "0.9.5"]
                              [org.clojure/clojure "1.5.1"]
                              ;; there are more, but only logging-relevant are added
                              [org.clojure/tools.logging "0.2.3"]
                              [org.slf4j/log4j-over-slf4j "1.6.6"]
                              [ch.qos.logback/logback-classic "1.0.13"]

                              ;; others are not present in pom.xml, but are present in `lib` folder
                              [org.slf4j/slf4j-api "1.7.5"]
                              [org.clojure/tools.macro "0.1.0"]
                              ; ... (there are more).

                              ;; There are also jars that are compiled-in into storm-core, but they are shaded
                              ;; See https://github.com/apache/storm/blob/master/storm-core/pom.xml#L394
                              ;; and https://github.com/apache/storm/blob/master/storm-dist/binary/src/main/assembly/binary.xml#L34
                              ]}
             :dev {:dependencies [;; unit testing framework
                                  [midje "1.6.3"]]}})
