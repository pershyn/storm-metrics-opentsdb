# Storm Metrics OpenTSDB Consumer

`storm-metrics-opentsdb` is a module for [Storm](http://storm.apache.org/) that enables metrics collection and reporting them directly to [OpenTSDB](http://opentsdb.net/)

To make sure your logic works as expected and as fast as expected, the metrics are absolutely necessary. Seeing them over time is leading to insights about bottlenecks, bugs, regressions and anomalies. You can measure on constant basis latencies, cache hits, traits of your data, anything that is critical in your case and can help to make your topology better.

This project is intended to be used with [grafana](http://grafana.org/), but can be used with others OpenTSDB frontends.

So far it is required to have `tcollector` with `udp_bridge` plugin installed on every node of the cluster where the topology runs - see reasoning in Dependencies.

Developed for apache storm and kafka-spout version 1.0.1 (starting from version 0.1.0 of this library)

## Usage

Add this as a dependency to your `pom.xml`

    <dependency>
      <groupId>storm.metric</groupId>
      <artifactId>opentsdb-metrics-consumer</artifactId>
      <version>0.1.1</version>
    </dependency>

or `project.clj`:

    [storm.metric/opentsdb-metrics-consumer "0.1.1"]

Then use it similar to other metrics consumers:

```
;; Clojure
(ns test.main
  (:import [backtype.storm StormSubmitter
                           Config]
           [storm.metric OpenTSDBMetricsConsumer]))

;; assume make-topology is our function that creates the topology
;; and is defined somewhere else
(declare make-topology)

;; ...

;; then similar code is used to prepare config and submit the topology
(let [consumer-config (OpenTSDBMetricsConsumer/makeConfig "storm.metrics.") ;; metrics prefix
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

Sorry, java usage example is coming. :-) (TODO)

In case you have properly configured OpenTSDB, tcollector with `udp_bridge` and TSD daemon running, the data should end up in OpenTSDB.

Then you can draw really nice graps in [grafana](http://grafana.org/) and see in realtime what is going on in the topology.

## Dependencies

Previous version `0.0.4` used the blocking TCP to talk directly to TSD daemon,
however high performance tests showed that TSD daemon tends to slow down sometimes, lag and even die.

In order to handle such scenarious properly, we have to admit that tsd service is not reliable and use asynchronous `java.nio` (or even `netty`) with proper timeouts, and also, which turned out to be harder, the connection should be proven to be functional.

The logic to maintain the connection and testing that it is up and alive and accepts connection seemed to me pretty complicated (you can look it up in `tcollector` implementation).

Unfortunately, I didn't have time for implementing this.

So I decided not to reimplement all of this in clojure, rather reuse the `tcollector` with `udp_bridge` plugin.

On the high loads from time to time some metrics are still lost because of the udp buffer overflow, but this turned to be much more reliable solution, than maintaining the connection to opentsdb.

The bad part is that now `tcollector` with `udp_bridge` plugin have to be installed on all the nodes. But, if you do the monitoring, it is quite probable that you have them already installed.

I have not found `udp_bridge` in alternative collectors, like [scollector](https://github.com/bosun-monitor/bosun/tree/master/cmd/scollector). But it doesn't mean it's not there.

As a proper solution without any collector-dependencies it may be good to write directly into HBase, but this is just a theory so far.

Apart from your user-defined metrics, all the storm system metrics are also available.

Unfortunately, I have not found a proper place where all of them are documented. So if you know one - you are welcome to add them.

### What else may be interesting:
- __kafka spout stats__ - all the stats that storm-kafka spouts produce. Kafka tools do not recognize custom storm format that is used by storm-kafka. This can be fixed in STORM-650. `host` tag defines where the spout is running and is there for each metric. `component-id` correlates with topic, since spout reads only from one topic. The metric is send on minute basis by default. This, afaik, can be configured. In OpenTSDB frontends we can get rates on metrics, downsampling and other operations.

  - `storm.metrics.$topology.kafkaOffset.spoutLag` tagged by `component-id`, `topic` and `partition`
  - `storm.metrics.$topology.kafkaOffset.totalSpoutLag` tagged by `component-id`, `topic` (redundant?)
  - `storm.metrics.$topology.kafkaPartition.fetchAPIMessageCount` tagged by `component-id`, `topic` and `partition`
  - `storm.metrics.$topology.kafkaPartition.fetchAPICallCount` tagged by `component-id`, `topic` and `partition`
  - `storm.metrics.$topology.kafkaPartition.fetchAPILatencyMax` tagged by `component-id`, `topic` and `partition`
  - `storm.metrics.$topology.kafkaPartition.fetchAPILatencyMean` tagged by `component-id`, `topic` and `partition`
  - `storm.metrics.$topology.kafkaOffset.latestEmittedOffset` tagged by `component-id`, `topic` and `partition`
  - `storm.metrics.$topology.kafkaOffset.latestTimeOffset` tagged by `component-id`, `topic` and `partition`
  - ... maybe more

- __storm system metrics__:
  - tagged by `host`
    - `storm.metrics.$topology.GC/PSScavenge.timeMs`
    - `storm.metrics.$topology.GC/PSMarkSweep.timeMs`
    - `storm.metrics.$topology.GC/PSMarkSweep.count`
    - `storm.metrics.$topology.GC/PSScavenge.count`
  - tagged by `host`, `component-id` and `taks-id`:
    - `storm.metrics.$topology.__ack-count.default`
    - `storm.metrics.$topology.__ack-count.your-stream-name-here`
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

```

### Metrics from kafka-spout

The metrics from kafka-spout have names custom names, see the `core_test.clj`.

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

No special cases here - all the metrics are mapped and written to opentsdb, with `:` changed to '.' and tags added where applicable:

- host - where this metrics was emitted from
- port - port where storm-worker is running.
- supervisor-id
- supervisor-name
- component-id

## TODO:

- [ ] review the connection logic, avoid the dependency on collectors.
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
