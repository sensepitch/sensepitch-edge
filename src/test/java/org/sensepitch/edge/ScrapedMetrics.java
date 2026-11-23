package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/// One scrape of a Prometheus registry, for assertions on single data points by metric name and
/// labels, and on the exposition text.
final class ScrapedMetrics {

  MetricSnapshots snapshots;
  Map<String, MetricSnapshot> name2snapshot = new HashMap<>();
  String prometheusText;

  ScrapedMetrics(MetricSnapshots snapshots) {
    this.snapshots = snapshots;
    snapshots.forEach(
        snapshot -> {
          name2snapshot.put(snapshot.getMetadata().getName(), snapshot);
        });
    PrometheusTextFormatWriter pw = PrometheusTextFormatWriter.builder().build();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      pw.write(out, snapshots);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    prometheusText = out.toString();
  }

  MetricSnapshot containsSnapshot(String metricName) {
    MetricSnapshot snapshot = name2snapshot.get(metricName);
    assertThat(snapshot).isNotNull().describedAs("snapshot available for metric %s", metricName);
    return snapshot;
  }

  DataPointSnapshot datapoint(String metricName) {
    MetricSnapshot snapshot = containsSnapshot(metricName);
    assertThat(snapshot.getDataPoints().size()).isEqualTo(1);
    DataPointSnapshot dataPointSnapshot = snapshot.getDataPoints().getFirst();
    return dataPointSnapshot;
  }

  DataPointSnapshot datapoint(String metricName, String... labelKeyValues) {
    Labels labels = Labels.of(labelKeyValues);
    MetricSnapshot snapshot = containsSnapshot(metricName);
    return snapshot.getDataPoints().stream()
        .filter(dataPointSnapshot -> labels.equals(dataPointSnapshot.getLabels()))
        .findFirst()
        .get();
  }

  GaugeSnapshot.GaugeDataPointSnapshot gauge(String metricName, String... labelKeyValues) {
    return (GaugeSnapshot.GaugeDataPointSnapshot) datapoint(metricName, labelKeyValues);
  }

  CounterSnapshot.CounterDataPointSnapshot counter(String metricName) {
    return (CounterSnapshot.CounterDataPointSnapshot) datapoint(metricName);
  }

  CounterSnapshot.CounterDataPointSnapshot counter(String metricName, String... labelKeyValues) {
    return (CounterSnapshot.CounterDataPointSnapshot) datapoint(metricName, labelKeyValues);
  }

  String getPrometheusExpositionText() {
    return prometheusText;
  }

  String getLinesContaining(String match) {
    return new BufferedReader(new StringReader(prometheusText))
        .lines()
        .filter(line -> line.contains(match))
        .collect(Collectors.joining("\n"));
  }

  @Override
  public String toString() {
    return prometheusText;
  }
}
