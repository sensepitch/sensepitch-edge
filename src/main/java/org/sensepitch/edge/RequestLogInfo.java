package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;

/**
 * @author Jens Wilke
 */
public interface RequestLogInfo {

  /** Used as a mock when the request was not received or was malformed */
  HttpVersion NIL_VERSION = new HttpVersion("NIL", 0, 0, false);
  /** Used as a mock when the request was not received or was malformed */
  HttpMethod NIL_METHOD = new HttpMethod("NIL");

  Channel channel();
  String requestId();

  /**
   * The http request received with augmented headers as it was sent to upstream.
   * If the request was not received but an error response with status code was
   * generated, this contains a mock request with request method {@link #NIL_METHOD}.
   */
  HttpRequest request();
  HttpResponse response();
  long contentBytes();
  long requestStartTimeMillis();

  /** Non-null if an error happened and the request did not complete successfully. */
  Throwable error();

  /** Trailing headers from LastHttpContent */
  HttpHeaders trailingHeaders();

  // TODO: calculate the deltas already

  long responseReceivedTimeNanos();

  /**
   * Time stamp when we received the response completely, or, when the output buffer is filled
   * for completely for the first time.
   */
  long responseStartedTimeNanos();

  long requestCompleteTimeNanos();

  long requestStartTimeNanos();
}
