package org.sensepitch.edge;

import io.netty.channel.Channel;
import lombok.Builder;

/// One diagnostic record, built by a [ProxyLogger] and handed to its [DiagnosticLogSink]. In
/// production the sink renders it with [DiagnosticLogSink#format(LogInfo)] and passes the text and
/// the throwable to Log4j2, whose layout adds time, thread and logger name. In tests the
/// `TestingLogSink` keeps the record unformatted, so tests can assert on its parts.
///
/// All fields but `level` are optional, a `null` part is left out of the text. The text is
/// `<channel ids> <operation> <message>`, e.g. `a1b2c3d4>e5f6a7b8 connection reset`, where a
/// channel id is the short text of the Netty channel id. The request log records the same id as
/// [RequestLogInfo#connectionId()], which ties a diagnostic line to the requests of the
/// connection. A [ProxyLogger] sets either `channel` or the pair `downstreamChannel` and
/// `upstreamChannel`, not both.
///
/// The record carries no time stamp and no logger name: the logging layout adds the time, and
/// the logger name is the one the sink was created for, see [DiagnosticLogSink#of(String)].
///
/// @param level severity, see the "Levels" in [ProxyLogger]; the sink decides whether the level
///   is written, see [DiagnosticLogSink#isEnabled]
/// @param channel the one connection the record is about, e.g. the client connection of an
///   error, rendered as its channel id at the start of the text
/// @param downstreamChannel the client connection of an exchange, for a record about both sides,
///   rendered as `<downstream id>>` followed by the upstream id
/// @param upstreamChannel the upstream connection of the same exchange, see `downstreamChannel`
/// @param operation a short name of what the code was doing, rendered before the message, e.g.
///   `channel read` for the trace of a read
/// @param message the text, without the throwable
/// @param error the throwable, passed to the logging framework apart from the text, so its stack
///   trace is printed by the layout; not part of [DiagnosticLogSink#format(LogInfo)]
/// @author Jens Wilke
@Builder
public record LogInfo(
    System.Logger.Level level,
    Channel channel,
    Channel downstreamChannel,
    Channel upstreamChannel,
    String operation,
    String message,
    Throwable error) {}
