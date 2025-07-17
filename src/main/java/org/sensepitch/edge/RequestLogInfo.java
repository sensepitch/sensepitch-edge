package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * @author Jens Wilke
 */
public interface RequestLogInfo {

  Channel channel();
  HttpRequest request();
  HttpResponse response();
  long contentBytes();
  long requestStartTime();

  /** Non-null if an error happened and the request did not complete successfully. */
  Throwable error();

  /** Trailing headers from LastHttpContent */
  HttpHeaders trailingHeaders();
}
