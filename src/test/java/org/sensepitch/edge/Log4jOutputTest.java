package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.System.Logger.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

/// The way of a record into Log4j2, [LogTarget.Log4jOutput], as in production. The loggers of the
/// proxy do not write to Log4j2 in tests, see [LogSink]; the rest of the logging is in
/// [LogTargetTest], which runs in parallel.
///
/// @author Jens Wilke
// alone: captures what arrives in Log4j2 and changes a Log4j2 level, both JVM wide
@Isolated
class Log4jOutputTest {

  static final String NAME = Log4jOutputTest.class.getName();

  @RegisterExtension Log4jCapture log4j = new Log4jCapture();

  @Test
  void errorIsForwardedToLog4jWithThrowable() {
    Throwable error = new Throwable("expected");
    LogTarget.Factory.log4j("org.sensepitch.LoggingSource")
        .log(LogInfo.builder().level(Level.ERROR).message("message text").error(error).build());
    assertThat(log4j.errors()).hasSize(1);
    LogEvent event = log4j.errors().getFirst();
    assertThat(event.getLoggerName()).isEqualTo("org.sensepitch.LoggingSource");
    assertThat(event.getMessage().getFormattedMessage()).isEqualTo("message text");
    assertThat(event.getThrown()).isSameAs(error);
  }

  /// With Log4j2 as the target, as in production, the level of the Log4j2 logger decides
  @Test
  void levelDecidesForWithTracing() {
    ProxyLogger log = new ProxyLogger.WithTracing(NAME, LogTarget.Factory.log4j(NAME));
    Configurator.setLevel(NAME, org.apache.logging.log4j.Level.INFO);
    try {
      assertThat(log.isTraceEnabled()).isFalse();
      log.trace("not visible at INFO");
      log.log(LogInfo.builder().level(Level.DEBUG).message("filtered by the target").build());
      log.info("visible at INFO");
      assertThat(log4j.messages(Log4jOutputTest.class)).containsExactly("visible at INFO");
    } finally {
      Configurator.setLevel(NAME, org.apache.logging.log4j.Level.TRACE);
    }
  }

  @Test
  void levelMapping() {
    for (Level level : Level.values()) {
      assertThat(LogTarget.Log4jOutput.toLog4j(level).name())
          .isEqualTo(level == Level.WARNING ? "WARN" : level.name());
    }
    assertThat(LogTarget.Log4jOutput.toLog4j(null)).isEqualTo(org.apache.logging.log4j.Level.INFO);
  }
}
