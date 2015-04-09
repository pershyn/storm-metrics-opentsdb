(ns storm-metrics-opentsdb.core-test
  (:require [clojure.test :refer :all]
            [storm.metric.OpenTSDBMetricsConsumer :refer :all])
  (:import [backtype.storm.metric.api IMetricsConsumer$TaskInfo
                                      IMetricsConsumer$DataPoint]))

(deftest a-test
  (testing "datapoint-to-metrics"
    (let [taskinfo (IMetricsConsumer$TaskInfo. "worker.host.name"
                                               12345
                                               "component-id"
                                               12                   ;srcTaskId
                                               77777777             ; timestamp
                                               2)                   ; updateIntervalSecs

          metric-id-header "hello-metric"
          timestamp (str (.timestamp taskinfo))
          tags (str "host=" (.srcWorkerHost taskinfo)
                    " port=" (.srcWorkerPort taskinfo)
                    " task-id=" (.srcTaskId taskinfo)
                    " component-id=" (.srcComponentId taskinfo))
          datapoint (IMetricsConsumer$DataPoint. "test-count-metric" 12)
          multi-count-datapoint (IMetricsConsumer$DataPoint. "test-multi-count-metric"
                                                             {"mapped-metric" 32
                                                              "mapped-metric2" 33})]
      (is (= "hello-metric.test-count-metric 77777777 12 host=worker.host.name port=12345 task-id=12 component-id=component-id"
             (storm.metric.OpenTSDBMetricsConsumer/datapoint-to-metrics metric-id-header timestamp tags datapoint)))
      (is (= (list "hello-metric.test-multi-count-metric.mapped-metric2 77777777 33 host=worker.host.name port=12345 task-id=12 component-id=component-id"
                   "hello-metric.test-multi-count-metric.mapped-metric 77777777 32 host=worker.host.name port=12345 task-id=12 component-id=component-id")
             (storm.metric.OpenTSDBMetricsConsumer/datapoint-to-metrics metric-id-header timestamp tags multi-count-datapoint))))))
