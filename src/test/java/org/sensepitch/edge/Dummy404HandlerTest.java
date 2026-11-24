package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.Test;

/// The placeholder of the routed handlers, see `Proxy.addHttpHandlers`. [SiteSelectorHandler]
/// replaces it for every routed request and answers an unrouted one itself, so in the pipeline it
/// is never reached; if it were, the request gets a 404 instead of no answer.
///
/// @author Jens Wilke
class Dummy404HandlerTest {

  @Test
  void requestIsAnsweredWithNotFoundAndItsContentDropped() {
    EmbeddedChannel channel = new EmbeddedChannel(new Dummy404Handler());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
    channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    HttpResponse response = channel.readOutbound();
    assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
    assertThat((Object) channel.readInbound()).isNull();
    channel.finishAndReleaseAll();
  }
}
