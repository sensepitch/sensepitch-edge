package org.sensepitch.edge;

import io.netty.util.concurrent.Ticker;

/// A buffer of encoded OTLP records, each framed as a `ScopeLogs.log_records` entry, so the buffer
/// can be sent as it is, see [OtlpBatchPayload].
///
/// @author Jens Wilke
final class OtlpBatch extends Batch {

  /// Room beyond the batch size for the record that crosses it, so a buffer does not grow
  static final int SLACK = 16 * 1024;

  final ProtobufWriter writer;

  OtlpBatch(int batchBytes, Ticker clock) {
    super(clock);
    writer = new ProtobufWriter(batchBytes + SLACK);
  }

  @Override
  int size() {
    return writer.size();
  }

  @Override
  void clear() {
    writer.reset();
  }
}
