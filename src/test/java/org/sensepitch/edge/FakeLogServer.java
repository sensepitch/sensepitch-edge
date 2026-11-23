package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLSession;

/// Stands in for the log server of a [HttpBatchSender]: it records each post, and the test answers
/// it, in posting order, with a status or a failure. Together with [FakeTime] the sender runs in
/// the test thread, without a server, timers or waits.
final class FakeLogServer implements HttpBatchSender.Transport {

  private final List<CompletableFuture<HttpResponse<Void>>> pending = new ArrayList<>();
  private final List<HttpRequest> requests = new ArrayList<>();

  @Override
  public CompletableFuture<HttpResponse<Void>> post(HttpRequest request) {
    requests.add(request);
    CompletableFuture<HttpResponse<Void>> answer = new CompletableFuture<>();
    pending.add(answer);
    return answer;
  }

  void succeed(int status) {
    pending.removeFirst().complete(response(status));
  }

  void fail(Throwable failure) {
    pending.removeFirst().completeExceptionally(failure);
  }

  HttpRequest onlyRequest() {
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }

  /// The requests posted so far, in the order they were posted.
  List<HttpRequest> requests() {
    return List.copyOf(requests);
  }

  private static HttpResponse<Void> response(int status) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return status;
      }

      @Override
      public HttpRequest request() {
        throw new UnsupportedOperationException();
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (first, second) -> true);
      }

      @Override
      public Void body() {
        return null;
      }

      @Override
      public Optional<HttpResponse<Void>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public URI uri() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }
    };
  }
}
