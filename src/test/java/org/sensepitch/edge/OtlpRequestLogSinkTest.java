package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.OtlpTestSupport.attributes;

import io.netty.handler.codec.http.HttpMethod;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The OTLP encoding of one request log record by [OtlpRequestLogSink], parsed with the official
/// OTLP message classes. The batches and their posting are in [OtlpLogDestinationTest].
class OtlpRequestLogSinkTest {

  /// Exactly the attributes the field definition emits, nothing lost or added in the encoding
  @Test
  void attributesMatchTheFieldDefinition() throws Exception {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.POST, 502)
            .withClientAddress("2001:db8::1")
            .withTls("TLSv1.3")
            .withError(new IllegalStateException("gone"));
    LogRecord record = LogRecord.parseFrom(OtlpRequestLogSink.encode(info));
    RequestLogFieldsWriterTest.RecordingSink expected = RequestLogFieldsWriterTest.fields(info);
    assertThat(record.getAttributesList())
        .extracting(KeyValue::getKey)
        .containsExactlyElementsOf(expected.fields.keySet());
    assertThat(attributes(record.getAttributesList()).get("client.address").getStringValue())
        .isEqualTo("2001:db8:0:0:0:0:0:1");
    assertThat(record.getSeverityText()).isEqualTo("WARN");
    assertThat(record.getSeverityNumberValue()).isEqualTo(13);
  }

  /// The sampled headers are encoded as a string array attribute
  @Test
  void sampleHeaders() throws Exception {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 200)
            .withSampleHeaders(List.of("Host: example.com", "Accept: */*"));
    ProtobufWriter out = new ProtobufWriter(1024);
    new OtlpRequestLogSink().encode(out, info);
    LogRecord record = LogRecord.parseFrom(out.toByteArray());
    AnyValue sampled =
        attributes(record.getAttributesList()).get("sensepitch.request.sample_headers");
    assertThat(sampled.getArrayValue().getValuesList())
        .extracting(AnyValue::getStringValue)
        .containsExactly("Host: example.com", "Accept: */*");
  }
}
