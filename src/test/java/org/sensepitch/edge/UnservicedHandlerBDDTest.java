package org.sensepitch.edge;


import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;


import net.serenitybdd.annotations.Step;
import net.serenitybdd.annotations.Steps;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

@ExtendWith(SerenityJUnit5Extension.class)
class UnservicedHandlerBDDTest {

  @Steps
  UnservicedSteps steps;

  @Test
  void allowedHost_passesThrough() {
    steps
      .given_configuration()
      .when_request_to("bar.com", "/")
      .then_request_is_passed_to_next_handler();
  }

  @Test
  void missingHost_resultsInBadRequestStatus() {
    steps
      .given_configuration()
      .when_request_without_host_to_uri("/")
      .then_response_status_is(BAD_REQUEST)
      .then_location_header_is_absent()
      .when_request_without_host_to_uri("/something")
      .then_response_status_is(BAD_REQUEST)
      .then_location_header_is_absent();

  }

  @Test
  void missingHostAfterSanitize_resultsInBadRequestStatus() {
    steps
      .given_configuration()
      .when_request_to(SanitizeHostHandler.MISSING_HOST, "/")
      .then_response_status_is(BAD_REQUEST)
      .then_location_header_is_absent();
  }

  @Test
  void unknownHost_resultsInBadRequestStatus() {
    steps
      .given_configuration()
      .when_request_to(SanitizeHostHandler.UNKNOWN_HOST, "/")
      .then_response_status_is(BAD_REQUEST)
      .then_location_header_is_absent();
  }

  @Test
  void mappedHost_redirectsToMappedTarget() {
    steps
      .given_configuration()
      .when_request_to("foo.com", "/")
      .then_response_status_is(FOUND)
      .then_location_header_is("https://www.foo.com");
  }

  @Test
  void otherHost_redirectsToDefaultTarget() {
    steps
      .given_configuration()
      .when_request_to("other.com", "/")
      .then_response_status_is(FOUND)
      .then_location_header_is("https://default.example");
  }

  @Test
  void otherHostWithUri_redirectsToDefaultTarget() {
    steps
      .given_configuration()
      .when_request_to("other.com", "/anything")
      .then_response_status_is(FOUND)
      .then_location_header_is("https://default.example" + UnservicedHandler.NOT_FOUND_URI);
  }

  @Test
  void secondRequestWorks() {
    steps
      .given_configuration()
      .when_request_without_host_to_uri("/")
      .then_response_status_is(BAD_REQUEST)
      .then_location_header_is_absent()
      .when_request_to("foo.com", "/")
      .then_response_status_is(FOUND)
      .then_location_header_is("https://www.foo.com");
  }

  public static class UnservicedSteps {

    private EmbeddedChannel channel;
    private HttpRequest request;
    private boolean forwarded;
    private Object forwardedMsg;
    private HttpResponse lastResponse;

    @Step(
      "When has common configuration: " +
      "passDomains=www.foo.com,bar.com,www.baz.com; defaultTarget=https://default.example")
    public UnservicedSteps given_configuration() {
      RedirectConfig cfg = RedirectConfig.builder()
        .passDomains(List.of("www.foo.com", "bar.com", "www.baz.com"))
        .defaultTarget("https://default.example")
        .build();
      channel = new EmbeddedChannel(new UnservicedHandler(cfg));
      return this;
    }

    @Step
    public UnservicedSteps when_request_to(String host, String uri) {
      request = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      request.headers().set(HttpHeaderNames.HOST, host);

      forwarded = channel.writeInbound(request);
      if (forwarded) {
        forwardedMsg = channel.readInbound();
      } else {
        lastResponse = (HttpResponse) channel.readOutbound();
        consumeIngressContent();
      }
      return this;
    }

    @Step
    public UnservicedSteps when_request_without_host_to_uri(String uri) {
      request = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      forwarded = channel.writeInbound(request);
      lastResponse = (HttpResponse) channel.readOutbound();
      consumeIngressContent();
      return this;
    }

    @Step
    public UnservicedSteps then_request_is_passed_to_next_handler() {
      assertThat(forwarded).isTrue();
      assertThat(forwardedMsg).isSameAs(request);
      return this;
    }

    @Step
    public UnservicedSteps then_response_status_is(HttpResponseStatus status) {
      assertThat(lastResponse.status()).isEqualTo(status);
      return this;
    }

    @Step
    public UnservicedSteps then_location_header_is_absent() {
      assertThat(lastResponse.headers().get(HttpHeaderNames.LOCATION)).isNull();
      return this;
    }

    @Step
    public UnservicedSteps then_location_header_is(String expected) {
      assertThat(lastResponse.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(expected);
      return this;
    }

    private void consumeIngressContent() {
      HttpContent chunk = new DefaultHttpContent(Unpooled.buffer());
      channel.writeInbound(chunk);
      assertThat(chunk.refCnt()).isZero();

      HttpContent last = new DefaultLastHttpContent(Unpooled.buffer());
      channel.writeInbound(last);
      assertThat(last.refCnt()).isZero();
    }
  }
}

