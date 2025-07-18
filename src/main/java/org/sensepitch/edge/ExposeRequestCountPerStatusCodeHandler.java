package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.CounterSnapshot.CounterDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.Unit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * @author Jens Wilke
 */
public class ExposeRequestCountPerStatusCodeHandler implements HasMultipleMetrics, RequestLogger {

  private final Map<Integer, LongAdder> counts = new ConcurrentHashMap<>();

  private final Histogram requestLatency = Histogram.builder()
    .name("http_request_duration_seconds")
    .help("HTTP request response time in seconds")
    .unit(Unit.SECONDS)
    .labelNames("ingress", "method", "status_code")
    .build();

  public ExposeRequestCountPerStatusCodeHandler() { }

  private MetricSnapshot internalCollect() {
    long now = System.currentTimeMillis();
    CounterSnapshot.Builder builder = CounterSnapshot.builder()
        .name("nginx_ingress_controller_requests")
        .help("Total HTTP requests, partitioned by status code")
        .unit(new Unit("requests"));
    counts.forEach((statusCode, adder) -> {
      CounterDataPointSnapshot dp = CounterDataPointSnapshot.builder()
            .value(adder.sum())
            .labels(Labels.of("code", statusCode + ""))
            .scrapeTimestampMillis(now)
            .build();
        builder.dataPoint(dp);
    });
    CounterSnapshot snapshot = builder.build();
    return snapshot;
  }

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    consumer.accept(requestLatency);
    consumer.accept(new Collector() {
      @Override
      public MetricSnapshot collect() {
        return internalCollect();
      }
    });
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    int statusCode = info.response().status().code();
    if (statusCode < 100) {
      statusCode = 99;
    }
    if (statusCode >= 600) {
      statusCode = 600;
    }
    counts.computeIfAbsent(statusCode, k -> new LongAdder()).increment();
    requestLatency
      .labelValues(
        info.request().headers().get(HttpHeaderNames.HOST),
        info.request().method().name(), statusCode + "")
      .observe(Unit.millisToSeconds(System.currentTimeMillis() - info.requestStartTime()));
  }

}
