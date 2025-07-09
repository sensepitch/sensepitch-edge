package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * @author Jens Wilke
 */
public interface RequestLogInfo {

  HttpRequest request();
  HttpResponse response();
  long contentBytes();
  long requestStartTime();
}
