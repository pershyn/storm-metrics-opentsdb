(ns storm.metric.OpenTSDBMetricsConsumer
  (:import (java.net DatagramSocket DatagramPacket InetAddress)
           (java.util Map HashMap)
           (backtype.storm.task TopologyContext IErrorReporter)
           (backtype.storm Config)
           (backtype.storm.metric.api IMetricsConsumer$TaskInfo
                                      IMetricsConsumer$DataPoint))
  (:gen-class :name storm.metric.OpenTSDBMetricsConsumer
              :implements [backtype.storm.metric.api.IMetricsConsumer]
              :methods [^:static [makeConfig [String Integer String]
                                  java.util.Map]]))

(def tsd-host-key "metrics.opentsdb.tsd_host")
(def tsd-port-key "metrics.opentsdb.tsd_port")
(def tsd-prefix-key "metrics.opentsdb.tsd_prefix")

;; Sockets and Streams that are used to write data to openTSDB.
;; connect to your localhost and use netcat to debug the output.
(def ^InetAddress inet-address (InetAddress/getByName "localhost"))

;InetAddress host = InetAddress.getByName("localhost");

(def socket (ref nil))

(def metric-id-header (ref nil))

(defn- connect []
  (dosync
    (ref-set socket (DatagramSocket.))))

(defn- disconnect []
  (dosync
    (.close ^DatagramSocket @socket)
    ))

(defn- send-data [data]
  (let [data (str "put " data "\n")
        bytes (.getBytes data)
        ;; convert data to binary payload accepted by DatagramPacket.
        dp (DatagramPacket. bytes
                            (count bytes)
                            inet-address
                            ;; using the standart port for tcollector udp_bridge
                            8953)]
    (.send ^DatagramSocket @socket
           dp)))

(defn- expand-complex-datapoint
  [^IMetricsConsumer$DataPoint dp]
  (if (or (map? (.value dp))
          (instance? java.util.AbstractMap (.value dp)))
    (vec (for [[k v] (.value dp)]
               [(str (.name dp) "/" k) v]))
    [[(.name dp) (.value dp)]]))

(defn kafkaPartition-data-point-to-metric
  "Handle the metrics format used by kafka-spout to report kafkaPartition stats
  See storm source code for metrics format details.

  This function is intended to be used with storm 0.9.5
  Because the metrics format is refactored in following versions.
  "
  [metric-id timestamp tags obj]
  (if (map? obj)
    (flatten (map (fn [[key val]]
                    (if-let [match (re-find #"(Partition\{host=.*,\spartition=(\d*)\}/)(.*)"
                                            key)]
                      ;["Partition{host=kafka-05.mytest.org:9092, partition=0}/fetchAPILatencyMean"
                      ; "Partition{host=kafka-05.mytest.org:9092, partition=0}/"
                      ; "0"
                      ; "fetchAPILatencyMean"]
                      (str metric-id "." (nth match 3) " "
                           timestamp " "
                           val " "
                           tags " "
                           "partition=" (nth match 2))
                      (str metric-id "." key " "
                           timestamp " "
                           val " "
                           tags)))
                  obj))))

(defn kafkaOffset-datapoint-to-metric
  "Handle the metrics format used by KafkaUtils to report kafkaOffset

  Intended to be used with kafka-spout v 0.9.5
  See the format in kafka-spout sources:

  https://github.com/apache/storm/blob/v0.9.5/external/storm-kafka/src/jvm/storm/kafka/KafkaUtils.java
  "
  [metric-id timestamp tags obj]
  (if (map? obj)
    (flatten (map (fn [[key val]]
                    (if-let [match (re-find #"partition_(\d*)/(\w*)"
                                            key)]
                      ;[\"partition_0/earliestTimeOffset\" \"0\" \"earliestTimeOffset\"]
                      (str metric-id "." (nth match 2) " "
                           timestamp " "
                           val " "
                           tags " "
                           "partition=" (nth match 1))
                      (str metric-id "." key " "
                           timestamp " "
                           val " "
                           tags)))
                  obj))))

(defn datapoint-to-metrics
  "Transforms storms datapoints to opentsdb metrics format"
  [metric-id-header
   timestamp
   tags
   ^IMetricsConsumer$DataPoint datapoint]

  ; The metrics are received in data points for task.
  ; Next values are in task id:
  ; - timestamp
  ; - srcWorkerHost
  ; - srcWorkerPort
  ; - srcTaskId
  ; - srcComponentId
  ; The data point has name and value.
  ; datapoint can be either a value with own name
  ; or a map in case of multi-count metrics

  ;; TODO: should tags be with underlines or minuses?
  ;; Is there a convention?...

  ;; TODO: handle storm-kafka metrics special case
  ;; https://github.com/apache/storm/blob/b2a8a77c3b307137527c706d0cd7635a6afe25bf/external/storm-kafka/src/jvm/storm/kafka/KafkaUtils.java
  (let [metric-id (str metric-id-header "." (.name datapoint))
        obj (.value datapoint)]
    (case (.name datapoint)
      "kafkaOffset" (kafkaOffset-datapoint-to-metric metric-id timestamp tags obj)
      "kafkaPartition" (kafkaPartition-data-point-to-metric metric-id timestamp tags obj)
      ;; default
      (if (number? obj)
        ;; datapoint is a Numberic value
        (str metric-id " " timestamp " " obj " " tags)
        ;; datapoint is a map of key-values
        (if (map? obj)
          (flatten (map (fn [[key val]] (str metric-id "." key " "
                                             timestamp " "
                                             val " "
                                             tags))
                        obj))
          ;; datapoint value is a collection of other datapoints?
          ;; Could this happen?
          (if (coll? obj)
            (throw (Exception. (str "Failed to parse coll metric: " obj)))
            (if (instance? HashMap obj)
              ;; skip the empty metric and process only maps
              (when-not (.isEmpty ^HashMap obj)
                (flatten (map (fn [[key val]] (str metric-id "." key " "
                                                   timestamp " "
                                                   val " "
                                                   tags))
                              obj)))
              (throw
                (Exception.
                  (format "Failed to parse metric: Not expected type: %s: %s"
                          (type obj)
                          obj ))))))))))

;; it is not possible to create static fields in class in clojure
;; like in is made in storm's Config, but it works with static method.
;; http://stackoverflow.com/questions/16252783/is-it-possible-to-use-clojures-gen-class-macro-to-generate-a-class-with-static?rq=1
(defn ^Map -makeConfig
  "Construct registration argument for OpenTSDBMetricsConsumer using the predefined key names."
  [tsd_host tsd_port tsd_prefix]
  {tsd-host-key tsd_host
   tsd-port-key tsd_port
   tsd-prefix-key tsd_prefix})

(defn -prepare
  [this
   ^Map topology-config
   ^Object consumer-config         ;; aka registrationArgument
   ^TopologyContext context
   ^IErrorReporter error-reporter]
  (assert (instance? Map consumer-config))
  ;; TODO: check that registrationArgument should be a Map??? Should it be (not) a map?

  (let [cc consumer-config
        default-tsd-prefix "storm.metrics."
        topology-name (get topology-config Config/TOPOLOGY_NAME)
        ;; TODO: assert the values are set
        tsd-prefix (get cc tsd-prefix-key default-tsd-prefix)
        tsd-prefix (if (not= \. (last tsd-prefix))
                     (str tsd-prefix ".")
                     tsd-prefix)]
    (dosync (ref-set metric-id-header (str tsd-prefix       ;; the point to the end of metric-id-header will be added during conversion
                                           topology-name)))
    (connect)))

;; TODO: Improve -  processed metrics can be added to buffer, where they can be read from with async routines, this may improve the througput.

(defn -handleDataPoints
  [this
   ^IMetricsConsumer$TaskInfo taskinfo
   datapoints]       ; ^Collection<DataPoint>
  (let [timestamp (str (.timestamp taskinfo))
        tags (str "host=" (.srcWorkerHost taskinfo) " "
                  "port=" (.srcWorkerPort taskinfo) " "
                  "task-id=" (.srcTaskId taskinfo) " "
                  "component-id=" (.srcComponentId taskinfo))
        metrics (->> datapoints
                     (map #(datapoint-to-metrics @metric-id-header
                                                 timestamp
                                                 tags
                                                 %))
                     (flatten)
                     (filter (complement nil?)) ;; filter out nil's
                     ;(filter (complement #(re-find #"__" %))) ;; filter out storm system metrics TODO: add this option to parameter
                     )]
  (doseq [m metrics]
    ;(try)
    ;; TODO: wrap this in try-catch.
    (send-data m))))

(defn -cleanup [this]
 (disconnect))
