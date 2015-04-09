# Storm Metrics OpenTSDB Consumer

storm-metrics-opentsdb is a module for [Storm](http://storm.apache.org/) that enables metrics collection and reporting them directly to [OpenTSDB](http://opentsdb.net/)

To make sure your logic works as expected and as fast as expected, the metrics are absolutely necessary. Seeing them over time is leading to insights about bottlenecks, bugs, regressions and anomalies. You can measure on constant basis latencies, cache hits, traits of your data, anything that is critical in your case and can help to make your topology better.

This project is intended to be used with [grafana](http://grafana.org/), but can be used with others OpenTSDB frontends.

## Usage

Add this as a dependency to your `pom.xml`

    <dependency>
      <groupId>storm.metric</groupId>
      <artifactId>storm-metrics-opentsdb</artifactId>
      <version>0.0.2</version>
    </dependency>

or `project.clj`:

    [storm.metric/opentsdb-metrics-consumer "0.0.2"]

Then use it:


```
;; CLojure
(ns test.main
  (:import [backtype.storm StormSubmitter
                           Config]
           [storm.metric OpenTSDBMetricsConsumer]))

;; assume make-topology is our function that creates the topology
;; and is defined somewhere else
(declare make-topology)

;; ...

;; then similar code is used to prepare config and submit the topology
(let [consumer-config (OpenTSDBMetricsConsumer/makeConfig "mytsdhost" ;; tsd host
                                                          (int 4242)  ;; tsd port
                                                          "storm.metrics.") ;; metrics prefix
      consumer-parallelism 1 ;; how many consumer bolts needed
      config (doto (HashMap. {;; here should be the config for storm topology,
                              ;; for example
                              Config/TOPOLOGY-DEBUG false
                              Config/TOPOLOGY_SLEEP_SPOUT_WAIT_STRATEGY_TIME_MS 100})
               ;; registering the consumer in topology config
               (Config/registerMetricsConsumer OpenTSDBMetricsConsumer
                                               consumer-config
                                               consumer-parallelism))]
  ;; Submitting the topology with config
  (StormSubmitter/submitTopology
    "topology-name"
    config           ;; this config has the record about OpenTSDBMetricsConsumer
    (make-topology)))
```

TODO: java usage example :-)

In case you have properly configured OpenTSDB and TSD daemon running, the data should end up in OpenTSDB.

Then you can draw really nice graps in [grafana](http://grafana.org/) and see in realtime what is going on in the topology.

Apart from your user-defined metrics, all the storm system metrics are also available.

Unfortunately I have not found a proper place where all of them are documented. So if you know one - you are welcome to add them.

What may be interesting:
- __kafka spout stats__ - all the stats that storm-kafka spouts produce. Kafka tools do not recognize custom storm format that is used by storm-kafka. This can be fixed in STORM-650. `host` tag defines where the spout is running and is there for each metric. `component-id` correlates with topic, since spout reads only from one topic. The metric is send on minute basis by default. This, afaik, can be configured. In OpenTSDB frontends we can get rates on metrics, downsampling and other operations.

  - `storm.metrics.$topology.kafkaOffset.spoutLag` tagged by `component-id` and `partition`.
  - `storm.metrics.$topology.kafkaPartition.fetchAPIMessageCount` tagged by `component-id`, `partition`
  - `storm.metrics.$topology.kafkaPartition.fetchAPICallCount` tagged by `partition`, `component-id` (correlates with topic)
  - `storm.metrics.$topology.kafkaPartition.fetchAPILatencyMax` tagged by `component-id`, `partition`
  - `storm.metrics.$topology.kafkaPartition.fetchAPILatencyMean` tagged by `component-id`, `partition`
  - `storm.metrics.$topology.kafkaOffset.latestEmittedOffset` tagged by `partition`, `component-id`
  - `storm.metrics.$topology.kafkaOffset.latestTimeOffset` tagged by `partition`, `component-id`
  - ... maybe more

- __storm system metrics__:
  - tagged by `host`
    - `storm.metrics.$topology.GC/PSScavenge.timeMs`
    - `storm.metrics.$topology.GC/PSMarkSweep.timeMs`
    - `storm.metrics.$topology.GC/PSMarkSweep.count`
    - `storm.metrics.$topology.GC/PSScavenge.count`
  - tagged by `host` and `component-id`:
    - `storm.metrics.$topology.__ack-count.default`
    - `storm.metrics.$topology.__fail-count.default`
    - `storm.metrics.$topology.__sendqueue.population`
  - ... and others

- __your custom metrics__:
while the overview of storm functioning can be obtained using the `storm-ui`, the picture _over time_ is limited by time-window from _now_ (10m, 3h, 1d). Sometimes it is really helpful to see some system metrics as a graph _over time_. The metrics from kafka spouts cannot be obtained from `storm-ui` at all (and it should be like this). Observing them _over time_ is also giving proper overview on topology functioning.

### Building / Installation

I am looking forward to put the jar to [maven.org](https://maven.org), but it is not yet there.

Please follow the instruction below to build it and install it in local repository.

```
# clone the repository:
git clone https://github.com/pershyn/storm-metrics-opentsdb

# use leiningen to compile, build jar and install it in local maven repository
lein compile; lein jar; lein install
```

## Tech details:

[It is not possible to create gen-class macro to generate a class with static field](http://stackoverflow.com/questions/16252783/is-it-possible-to-use-clojures-gen-class-macro-to-generate-a-class-with-static?rq=1), so the static method is used to create a map with parameters, the way it is consumed by storm.

The metrics are received in data points for task.
Next values are in task id:
- timestamp
- srcWorkerHost
- srcWorkerPort
- srcTaskId
- srcComponentId
The data point has name and value.

the value is an object, and basing from examples below - it is a map.
The key in these maps are often composed with `/`

This is to get a feel of the data, how it is printed by LoggingMetricsConsumer:
```
2014-09-08 17:25:37,925 302817   1410191857     storm-12.mytest.org:6705         -1:__system    memory/nonHeap          {unusedBytes=496928, maxBytes=136314880, usedBytes=34106080, initBytes=24576000, committedBytes=34603008, virtualFreeBytes=102208800}

2014-09-08 17:57:37,925 302817   1410191857     storm-12.mytest.org:6705         -1:__system    memory/nonHeap          {unusedBytes=496928, maxBytes=136314880, usedBytes=34106080, initBytes=24576000, committedBytes=34603008, virtualFreeBytes=102208800}
```

Another several examples below:

```

  GC/PSMarkSweep
  {count=0, timeMs=0}

  memory/nonHeap
  {unusedBytes=385408, maxBytes=136314880, usedBytes=33103488, initBytes=24576000, committedBytes=33488896, virtualFreeBytes=103211392}

  __emit-count
  {}

  __sendqueue
  {write_pos=90057, read_pos=90057, capacity=2048, population=0}

# for example from kafka spout
kafkaPartition:
  {
   Partition{host=kafka-05.mytest.org:9092, partition=3}/fetchAPILatencyMean=309.0,
   Partition{host=kafka-05.mytest.org:9092, partition=3}/fetchAPICallCount=1,
   Partition{host=kafka-05.mytest.org:9092, partition=3}/fetchAPILatencyMax=309,
   Partition{host=kafka-05.mytest.org:9092, partition=3}/fetchAPIMessageCount=8350,

   Partition{host=kafka-06.mytest.org:9092, partition=0}/fetchAPIMessageCount=0,
   Partition{host=kafka-06.mytest.org:9092, partition=0}/fetchAPILatencyMean=null,
   Partition{host=kafka-06.mytest.org:9092, partition=0}/fetchAPILatencyMax=null,
   Partition{host=kafka-06.mytest.org:9092, partition=0}/fetchAPICallCount=0,

   Partition{host=kafka-07.mytest.org:9092, partition=1}/fetchAPIMessageCount=8082
   Partition{host=kafka-07.mytest.org:9092, partition=1}/fetchAPILatencyMean=99.0,
   Partition{host=kafka-07.mytest.org:9092, partition=1}/fetchAPICallCount=1,
   Partition{host=kafka-07.mytest.org:9092, partition=1}/fetchAPILatencyMax=99,

   Partition{host=kafka-08.mytest.org:9092, partition=2}/fetchAPIMessageCount=0,
   Partition{host=kafka-08.mytest.org:9092, partition=2}/fetchAPILatencyMax=null,
   Partition{host=kafka-08.mytest.org:9092, partition=2}/fetchAPICallCount=0,
   Partition{host=kafka-08.mytest.org:9092, partition=2}/fetchAPILatencyMean=null,
  }

kafkaOffset
  {partition_3/latestTimeOffset=30109296760,
   totalSpoutLag=610889380,
   totalLatestTimeOffset=30109296760,
   totalLatestEmittedOffset=29498407380,
   partition_3/latestEmittedOffset=29498407380,
   totalEarliestTimeOffset=29498407377,
   partition_3/earliestTimeOffset=29498407377,
   partition_3/spoutLag=610889380}

```

As we may see, these metrics are quite custom....

### Metrics from kafka-spout

The metrics from kafka-spout have names like this "Partition{host=mykafkahost:9092, partition=2}/fetchAPIMessageCount". Looking forward to come up with some standard naming approach in kafka spout so it can be processed easily by machines then.

For kafka-spout there a special case.

What is done so far in openTSDBMetricsConsumer with kafka:

1 - Extract and assign tags:

- host (without port)
- partition
- topic

2 - Extract metrics for partition:
- PartitionFetchAPI.LatencyMean
- PartitionFetchAPI.CallCount
- PartitionFetchAPI.LatencyMax
- PartitionFetchAPI.MessageCount
- latestTimeOffset (tagged by partition number) and kafka spout id.
- latestEmittedOffset (tagged by partition number) and kafka spout id.
- earliestTimeOffset (tagged by partition number) and kafka spout id.
- spoutLag (tagged by partition number) and kafka spout id.

3 - Extract metrics for kafka-spout:
- totalSpoutLag
- totalLatestTimeOffset
- totalLatestEmittedOffset
- totalEarliestTimeOffset

### Processing storm metrics:

No special cases here - all the metrics are mapped and written to opentsdb, with `/` changed to '.' and tags added where applicable:

- host - where this metrics was emitted from
- port - port where storm-worker is running.
- supervisor-id
- supervisor-name
- component-id

## TODO:

- [ ] consider to use wrapping an OutputStreamWriter within a BufferedWriter so as to avoid frequent converter invocations. For example:  `Writer out = new BufferedWriter(new OutputStreamWriter(System.out));`
- [ ] optional processing of storm system metrics
- [ ] optional kafka special case


## Other projects that may be interesting

- https://github.com/yieldbot/marceline#metrics
- https://github.com/staslev/storm-metrics-reporter
- https://github.com/endgameinc/storm-metrics-statsd/

## License

Licensed under the Apache License, Version 2.0 (the "License"); you may
not use this file except in compliance with the License. You may obtain
a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
