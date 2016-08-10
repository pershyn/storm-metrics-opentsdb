(ns storm-metrics-opentsdb.core-test
  (:require [midje.sweet :refer :all]
            [storm.metric.OpenTSDBMetricsConsumer :refer :all])
  (:import [org.apache.storm.metric.api IMetricsConsumer$TaskInfo
                                        IMetricsConsumer$DataPoint]))

(facts
  "about OpenTSDBMetricConsumer"
  (let [taskinfo (IMetricsConsumer$TaskInfo. "worker.host.name"
                                             12345
                                             "component-id"
                                             12                   ;srcTaskId
                                             77777777             ; timestamp
                                             2)                   ; updateIntervalSecs
        kafka-offset-datapoint-obj {"topic1/partition_0/latestEmittedOffset" 86664047241
                                    "topic1/totalSpoutLag" 1467
                                    "topic1/totalLatestTimeOffset" 86664048708
                                    "topic1/totalLatestEmittedOffset" 86664047241
                                    "topic1/partition_0/spoutLag" 1467
                                    "topic1/partition_0/earliestTimeOffset" 86378711184
                                    "topic1/totalEarliestTimeOffset" 86378711184
                                    "topic1/partition_0/latestTimeOffset" 86664048708}
        kafka-offset-datapoint-obj-2 {"topic2/partition_7/spoutLag" 6099
                                      "topic2/partition_7/latestEmittedOffset" 29229164962
                                      "topic2/partition_7/latestTimeOffset" 29229171061
                                      "topic2/partition_7/earliestTimeOffset" 29009184937
                                      "topic2/totalSpoutLag" 6099
                                      "topic2/totalLatestTimeOffset" 29229171061
                                      "topic2/totalLatestEmittedOffset" 29229164962
                                      "topic2/totalEarliestTimeOffset" 29009184937}
        metric-id-header "hello-metric"
        timestamp (str (.timestamp taskinfo))
        tags (str "host=" (.srcWorkerHost taskinfo)
                  " port=" (.srcWorkerPort taskinfo)
                  " task-id=" (.srcTaskId taskinfo)
                  " component-id=" (.srcComponentId taskinfo))
        datapoint (IMetricsConsumer$DataPoint. "test-count-metric" 12)
        multi-count-datapoint (IMetricsConsumer$DataPoint. "test-multi-count-metric"
                                                           {"mapped-metric" 32
                                                            "mapped-metric2" 33})
        multi-count-datapoint-with-colons (IMetricsConsumer$DataPoint. "__process-latency"
                                                                       {"bolt-number-one:default" 6.06896551724138
                                                                        "bolt-number-two:default" 7.21590909090909})
        kafka-offset-datapoint (IMetricsConsumer$DataPoint. "kafkaOffset"
                                                            kafka-offset-datapoint-obj)]
    (facts
      "about datapoint-to-metrics"
      (fact "works as expected on normal datapoint"
            (storm.metric.OpenTSDBMetricsConsumer/datapoint-to-metrics metric-id-header timestamp tags datapoint)
            => (just #{"hello-metric.test-count-metric 77777777 12 host=worker.host.name port=12345 task-id=12 component-id=component-id"}))

      (fact "works as expected on multi-count-datapoint"
            (storm.metric.OpenTSDBMetricsConsumer/datapoint-to-metrics metric-id-header timestamp tags multi-count-datapoint)
            => (just #{"hello-metric.test-multi-count-metric.mapped-metric2 77777777 33 host=worker.host.name port=12345 task-id=12 component-id=component-id"
                       "hello-metric.test-multi-count-metric.mapped-metric 77777777 32 host=worker.host.name port=12345 task-id=12 component-id=component-id"}))

      (fact "works as expected on multi-count-datapoint with colons in names"
            (storm.metric.OpenTSDBMetricsConsumer/datapoint-to-metrics metric-id-header timestamp tags multi-count-datapoint-with-colons)
            => (just #{"hello-metric.__process-latency.bolt-number-one.default 77777777 6.06896551724138 host=worker.host.name port=12345 task-id=12 component-id=component-id"
                       "hello-metric.__process-latency.bolt-number-two.default 77777777 7.21590909090909 host=worker.host.name port=12345 task-id=12 component-id=component-id"}))

      (fact "works as expected on kafkaOffset datapoint"
            (storm.metric.OpenTSDBMetricsConsumer/datapoint-to-metrics metric-id-header timestamp tags kafka-offset-datapoint)
            => (just #{"hello-metric.kafkaOffset.totalSpoutLag 77777777 1467 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1"
                       "hello-metric.kafkaOffset.earliestTimeOffset 77777777 86378711184 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1 partition=0"
                       "hello-metric.kafkaOffset.totalLatestEmittedOffset 77777777 86664047241 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1"
                       "hello-metric.kafkaOffset.totalLatestTimeOffset 77777777 86664048708 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1"
                       "hello-metric.kafkaOffset.spoutLag 77777777 1467 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1 partition=0"
                       "hello-metric.kafkaOffset.totalEarliestTimeOffset 77777777 86378711184 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1"
                       "hello-metric.kafkaOffset.latestEmittedOffset 77777777 86664047241 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1 partition=0"
                       "hello-metric.kafkaOffset.latestTimeOffset 77777777 86664048708 host=worker.host.name port=12345 task-id=12 component-id=component-id topic=topic1 partition=0"})))

    (facts
      "about kafkaOffset-datapoint-to-metric"
      (fact
        "metrics from kafka spout version 1.0.1 are parsed correctly"
        (kafkaOffset-datapoint-to-metric "kafkaOffset" 1439199951 "host=test-host" kafka-offset-datapoint-obj)
        => (just #{"kafkaOffset.totalSpoutLag 1439199951 1467 host=test-host topic=topic1"
                   "kafkaOffset.earliestTimeOffset 1439199951 86378711184 host=test-host topic=topic1 partition=0"
                   "kafkaOffset.totalLatestEmittedOffset 1439199951 86664047241 host=test-host topic=topic1"
                   "kafkaOffset.totalLatestTimeOffset 1439199951 86664048708 host=test-host topic=topic1"
                   "kafkaOffset.spoutLag 1439199951 1467 host=test-host topic=topic1 partition=0"
                   "kafkaOffset.totalEarliestTimeOffset 1439199951 86378711184 host=test-host topic=topic1"
                   "kafkaOffset.latestEmittedOffset 1439199951 86664047241 host=test-host topic=topic1 partition=0"
                   "kafkaOffset.latestTimeOffset 1439199951 86664048708 host=test-host topic=topic1 partition=0"}))
      (fact
        "another metrics from kafka spout version 1.0.1 are parsed correctly"
        (kafkaOffset-datapoint-to-metric "kafkaOffset" 1439199951 "host=test-host2" kafka-offset-datapoint-obj-2)
        => (just #{"kafkaOffset.earliestTimeOffset 1439199951 29009184937 host=test-host2 topic=topic2 partition=7"
                   "kafkaOffset.totalSpoutLag 1439199951 6099 host=test-host2 topic=topic2"
                   "kafkaOffset.totalLatestEmittedOffset 1439199951 29229164962 host=test-host2 topic=topic2"
                   "kafkaOffset.totalLatestTimeOffset 1439199951 29229171061 host=test-host2 topic=topic2"
                   "kafkaOffset.spoutLag 1439199951 6099 host=test-host2 topic=topic2 partition=7"
                   "kafkaOffset.latestTimeOffset 1439199951 29229171061 host=test-host2 topic=topic2 partition=7"
                   "kafkaOffset.totalEarliestTimeOffset 1439199951 29009184937 host=test-host2 topic=topic2"
                   "kafkaOffset.latestEmittedOffset 1439199951 29229164962 host=test-host2 topic=topic2 partition=7"})))

    (facts
      "about kafkaPartition-data-point-to-metric"
      (let [datapoint-obj {"Partition{host=kafka-05.mytest.org:9092, topic=topic1, partition=0}/fetchAPICallCount" 469
                           "Partition{host=kafka-05.mytest.org:9092, topic=topic1, partition=0}/fetchAPILatencyMax" 741
                           "Partition{host=kafka-05.mytest.org:9092, topic=topic1, partition=0}/fetchAPIMessageCount" 196020
                           "Partition{host=kafka-05.mytest.org:9092, topic=topic1, partition=0}/fetchAPILatencyMean" 6.072}]
        (fact
          "metrics from kafka spout version 1.0.1 are parsed correctly"
          (kafkaPartition-data-point-to-metric "kafkaPartition" 1439199951 "host=testhost2" datapoint-obj)
          => (just #{"kafkaPartition.fetchAPILatencyMean 1439199951 6.072 host=testhost2 topic=topic1 partition=0"
                     "kafkaPartition.fetchAPILatencyMax 1439199951 741 host=testhost2 topic=topic1 partition=0"
                     "kafkaPartition.fetchAPIMessageCount 1439199951 196020 host=testhost2 topic=topic1 partition=0"
                     "kafkaPartition.fetchAPICallCount 1439199951 469 host=testhost2 topic=topic1 partition=0"}))))))
