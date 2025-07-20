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

  /**
   * Time from connection establishment until request was received completely.
   * For a second request in a keep alive connection this includes waiting time.
   * If the request was never received completely or was erroneous, the end time
   * is when the error response was generated.
   */
  long requestReceiveTimeDeltaNanos();

  /**
   * Time between the ingress request was received completely and a significant response was
   * received. Either the response was received completely, or it filled the output buffer, causing
   * throttling until the client received it. This means the response time does not vary in case
   * clients have a slow network. If the response from upstream was never received completely
   * the end time is when the error response was generated.
   */
  long responseTimeDeltaNanos();

  /**
   * Total time from starting the request until the response was received completely by the
   * client. This contains slow network on the client side, however, excludes connection
   * handshake and transmission of the request headers.
   */
  long totalTimeDeltaNanos();

}
