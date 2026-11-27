package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// [ProxyLogger] and the formatting of [DiagnosticLogSink], captured per thread. The way into Log4j2 needs
/// the JVM wide capture and is in [Log4jOutputTest].
///
/// @author Jens Wilke
public class DiagnosticLogSinkTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  public void proxyLoggerUsesClassNameAndKeepsCause() {
    Throwable error = new Throwable("expected");
    ProxyLogger.get(DiagnosticLogSinkTest.class).error("failed", error);
    assertThat(logs.messages(DiagnosticLogSinkTest.class)).containsExactly("failed");
    assertThat(logs.errors().getFirst().error()).isSameAs(error);
  }

  static final String NAME = DiagnosticLogSinkTest.class.getName();

  /// Tracing is available by default, the level decides. Tests run the proxy's loggers at TRACE,
  /// see `log4j2-test.xml`. The trace calls are run for coverage, their output is not checked.
  @Test
  public void defaultLoggerTraces() {
    ProxyLogger log = ProxyLogger.get(DiagnosticLogSinkTest.class);
    assertThat(log).isInstanceOf(ProxyLogger.WithTracing.class);
    assertThat(log.isTraceEnabled()).isTrue();
    EmbeddedChannel down = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    EmbeddedChannel up = new EmbeddedChannel();
    log.trace("plain");
    log.trace(down, "down");
    log.trace(down, up, "both");
    log.traceChannelRead(down.pipeline().firstContext(), "a message");
  }

  /// The kill switch `SENSEPITCH_EDGE_DISABLE_TRACE` selects a logger without tracing, everything
  /// else is logged.
  @Test
  public void errorOnlyNeverTraces() {
    ProxyLogger log = ProxyLogger.create(NAME, true);
    assertThat(log).isInstanceOf(ProxyLogger.ErrorOnly.class);
    assertThat(log.isTraceEnabled()).isFalse();
    EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    log.trace("plain");
    log.trace(channel, "down");
    log.trace(channel, channel, "both");
    log.traceChannelRead(channel.pipeline().firstContext(), "a message");
    log.error("still logged");
    assertThat(logs.messages(DiagnosticLogSinkTest.class)).containsExactly("still logged");
  }

  @Test
  public void errorsWarningsAndInfo() {
    ProxyLogger log = ProxyLogger.get(DiagnosticLogSinkTest.class);
    EmbeddedChannel down = new EmbeddedChannel();
    EmbeddedChannel up = new EmbeddedChannel();
    String d = down.id().asShortText();
    String u = up.id().asShortText();
    Throwable cause = new Throwable("expected");
    log.error(down, cause);
    log.error(down, "with message");
    log.error(down, "with cause", cause);
    log.error(down, up, "both channels", cause);
    log.error("plain", cause);
    log.warn("a warning");
    log.info("an info");
    assertThat(logs.messages(DiagnosticLogSinkTest.class))
        .containsExactly(
            d,
            d + " with message",
            d + " with cause",
            d + ">" + u + " both channels",
            "plain",
            "a warning",
            "an info");
    assertThat(log.channelId(null)).isEqualTo("null");
  }

  /// Channel, operation and message are joined with spaces. Only trace records carry an
  /// operation, and tests do not capture those, so the format is checked directly.
  @Test
  public void formatWithChannelAndOperation() {
    EmbeddedChannel channel = new EmbeddedChannel();
    LogInfo info =
        LogInfo.builder().channel(channel).operation("channel read").message("a message").build();
    assertThat(DiagnosticLogSink.format(info))
        .isEqualTo(channel.id().asShortText() + " channel read a message");
  }
}
