(ns storm.metric.OpenTSDBMetricsConsumer
  (:import (java.net DatagramSocket DatagramPacket InetAddress)
           (org.apache.storm.task TopologyContext IErrorReporter)
           (org.apache.storm Config)
           (org.apache.storm.metric.api IMetricsConsumer$TaskInfo
                                        IMetricsConsumer$DataPoint))
  (:require [org.apache.storm.log :as log])
  (:gen-class :name storm.metric.OpenTSDBMetricsConsumer
              :implements [org.apache.storm.metric.api.IMetricsConsumer]
              :methods [^:static [makeConfig [String]
                                  java.util.Map]]))

(def tsd-prefix-key "metrics.opentsdb.tsd_prefix")

;; Sockets and Streams that are used to write data to openTSDB.
;; connect to your localhost and use netcat to debug the output.
(def ^InetAddress inet-address (InetAddress/getByName "localhost"))

;; using the standart port for tcollector udp_bridge
(def ^Integer tcollector-udp-bridge-port (int 8953))

(def socket (atom nil))
(def metric-id-header (atom nil))

(defn- connect! []
  (reset! socket (DatagramSocket.)))

(defn- disconnect! []
  (.close ^DatagramSocket @socket))

(defn- send-data 
  "Sends data to tcollector's udp_bridge plugin port."
  [data]
  (let [data (str "put " data "\n")
        bytes (.getBytes data)
        ;; convert data to binary payload accepted by DatagramPacket.
        dp (DatagramPacket. bytes
                            (count bytes)
                            inet-address
                            tcollector-udp-bridge-port)]
    (.send ^DatagramSocket @socket
           dp)))

(defn kafkaPartition-data-point-to-metric
  "Handle the metrics format used by kafka-spout to report kafkaPartition stats

  This function is intended to be used with storm 1.0.1
  See the format in kafka-spout sources:
  
  https://github.com/apache/storm/blob/v1.0.1/external/storm-kafka/src/jvm/org/apache/storm/kafka/PartitionManager.java#L127
   and
  https://github.com/apache/storm/blob/v1.0.1/external/storm-kafka/src/jvm/org/apache/storm/kafka/Partition.java#L69

  Basically keys look like this 'Partition{host=brokerX:9092, topic=mytopic, partition=N}/metricName'
  "
  [metric-id timestamp tags obj]
  (if (or (instance? java.util.Map obj)
          (map? obj))
      (map (fn [[key val]]
                    (if-let [match (re-find #"Partition\{host=.*,\stopic=(.*),\spartition=(\d*)\}/(.*)"
                                            key)]
                      (str metric-id "." (nth match 3) " "
                           timestamp " "
                           val " "
                           tags " "
                           "topic=" (nth match 1)
                           "partition=" (nth match 2))
                      (str metric-id "." key " "
                           timestamp " "
                           val " "
                           tags)))
                  obj)
    (log/log-warn "Failed to parse kafka datapoint: " obj ", type:" (type obj))))

(defn kafkaOffset-datapoint-to-metric
  "Handle the metrics format used by KafkaUtils to report kafkaOffset

  Intended to be used with kafka-spout v 1.0.1
  See the format in kafka-spout sources:

  https://github.com/apache/storm/blob/v1.0.1/external/storm-kafka/src/jvm/org/apache/storm/kafka/KafkaUtils.java#L127

  Basically keys look like this 'topic/partition_N/metricName' for partition specific offset metrics and
  like this 'topic/metricName' for total offset metrics
  "
  [metric-id timestamp tags obj]
  (if (or (instance? java.util.Map obj)
          (map? obj))
    (map (fn [[key val]]
                      (let [parts (clojure.string/split key #"/" 3)]
                        (case (count parts)
                          2 (str metric-id "." (nth parts 1) " "
                                 timestamp " "
                                 val " "
                                 tags
                                 " topic=" (nth parts 0)) 
                          3 (str metric-id "." (nth parts 2) " "
                                 timestamp " "
                                 val " "
                                 tags
                                 " topic=" (nth parts 0) 
                                 " partition=" (first (re-find #"(\d*)" (nth parts 1)))))))
         obj)
    (log/log-warn "Failed to parse kafka datapoint: " obj ", type:" (type obj))))

(defn datapoint-to-metrics
  "Transforms storms datapoints to opentsdb metrics format"
  [metric-id-header
   timestamp
   tags
   ^IMetricsConsumer$DataPoint datapoint]
  ; The data point has name and value.
  ; datapoint can be either a value with own name
  ; or a map in case of multi-count metrics
  (let [metric-id (str metric-id-header "." (.name datapoint))
        obj (.value datapoint)]
    (case (.name datapoint)
      ;; here we handle storm-kafka metrics special case, see details at github
      ;; https://github.com/apache/storm/blob/b2a8a77c3b307137527c706d0cd7635a6afe25bf/external/storm-kafka/src/jvm/storm/kafka/KafkaUtils.java
      "kafkaOffset" (kafkaOffset-datapoint-to-metric metric-id timestamp tags obj)
      "kafkaPartition" (kafkaPartition-data-point-to-metric metric-id timestamp tags obj)
      ;; default
      (if (number? obj)
        ;; datapoint is a Numberic value
        [(str metric-id " " timestamp " " obj " " tags)]
        ;; datapoint is a map of key-values
        (if (or (map? obj)
                (instance? java.util.Map obj))
          (map (fn [[key val]] (format "%s.%s %s %s %s"
                                                metric-id
                                                (clojure.string/replace (str key) ":" ".")
                                                timestamp
                                                val
                                                tags))
               obj)
          (throw
            (Exception.
              (format "Failed to parse metric: Not expected type: %s: %s"
                      (type obj)
                      obj ))))))))

;; it is not possible to create static fields in class in clojure
;; like in is made in storm's Config, but it works with static method.
;; http://stackoverflow.com/questions/16252783/is-it-possible-to-use-clojures-gen-class-macro-to-generate-a-class-with-static?rq=1
(defn ^java.util.Map -makeConfig
  "Construct registration argument for OpenTSDBMetricsConsumer using the predefined key names."
  [tsd_prefix]
  {tsd-prefix-key tsd_prefix})

(defn -prepare
  [this
   ^java.util.Map topology-config
   ^Object consumer-config         ;; aka registrationArgument
   ^TopologyContext context
   ^IErrorReporter error-reporter]
  (assert (instance? java.util.Map consumer-config))
  ;; TODO: check that registrationArgument should be a Map??? Should it be (not) a map?

  (let [cc consumer-config
        default-tsd-prefix "storm.metrics."
        topology-name (get topology-config Config/TOPOLOGY_NAME)
        ;; TODO: assert the values are set
        tsd-prefix (get cc tsd-prefix-key default-tsd-prefix)
        tsd-prefix (if (not= \. (last tsd-prefix))
                     (str tsd-prefix ".")
                     tsd-prefix)]
    (reset! metric-id-header (str tsd-prefix       ;; the point to the end of metric-id-header will be added during conversion
                                  topology-name)))
    (connect!))

;; TODO: Improve -  processed metrics can be added to buffer, where they can be read from with async routines, this may improve the througput.

(defn -handleDataPoints
  [this
   ^IMetricsConsumer$TaskInfo taskinfo
   datapoints]       ; ^Collection<DataPoint>
  ; The metrics are received as a collection of DataPoints for specific TaskInfo object.
  ; The following values are contained the TaskInfo:
  ; - timestamp
  ; - srcWorkerHost
  ; - srcWorkerPort
  ; - srcTaskId
  ; - srcComponentId
  (let [timestamp (str (.timestamp taskinfo))
        tags (str "host=" (.srcWorkerHost taskinfo) " "
                  "port=" (.srcWorkerPort taskinfo) " "
                  "task-id=" (.srcTaskId taskinfo) " "
                  "component-id=" (.srcComponentId taskinfo))
        metrics (->> datapoints
                     (mapcat #(datapoint-to-metrics @metric-id-header
                                                    timestamp
                                                    tags
                                                    %))
                     (remove nil?) ;; filter out invalid datapoints
                     ;(filter (complement #(re-find #"__" %))) ;; filter out storm system metrics TODO: add this option to parameter
                     )]
  (doseq [m metrics]
    (try (send-data m)
         (catch Exception e (log/log-error "Failed to send metric: " m))))))

(defn -cleanup [this]
 (disconnect!))
