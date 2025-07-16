package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;

/**
 * @author Jens Wilke
 */
public interface RequestLogger {

  void logRequest(RequestLogInfo info);

}
