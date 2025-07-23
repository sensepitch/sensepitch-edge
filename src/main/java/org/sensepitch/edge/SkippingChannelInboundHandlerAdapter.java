package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/**
 * Skips content messages and releases the buffer, if this handler is reacting to the request
 * and is not interested in the actual content.
 *
 * @author Jens Wilke
 */
public class SkippingChannelInboundHandlerAdapter extends ChannelInboundHandlerAdapter {

  private boolean skipContent;

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (skipContent && msg instanceof HttpContent) {
      ReferenceCountUtil.release(msg);
      if (msg  instanceof LastHttpContent) {
        skipContent = false;
      }
      return;
    }
    super.channelRead(ctx, msg);
  }

  /**
   * Keep connection open.
   */
  protected void skipFollowingContent(ChannelHandlerContext ctx) {
    skipContent = true;
  }

}
