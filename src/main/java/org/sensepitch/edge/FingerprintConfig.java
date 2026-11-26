package org.sensepitch.edge;

import lombok.Builder;

/// Configuration of the client fingerprints and of the header samples to study them, see
/// [FingerprintHandler] and `doc/design/browser-fingerprinting.md`. A reload applies it, JA4 to
/// new connections, the rest to new requests.
///
/// @param ja4 the JA4 TLS fingerprint, see [Ja4Handler]
/// @param sp1 the request header fingerprint, version 1, see [Sp1Fingerprint]
/// @param sampleHeadersInterval if positive, about one in this many requests, picked at random,
///   also logs its request headers as `sensepitch.request.sample_headers`, to study what
///   identifies a client. The headers are taken as the client sent them; cookies, credentials,
///   `traceparent` and the forwarding headers are left out, see
///   [FingerprintHandler#SAMPLE_HEADERS_EXCLUDED]. Every JSON and OTLP destination logs the sample
///   of the same requests, the plain text format none. `0`, the default, turns it off.
@Builder(toBuilder = true)
public record FingerprintConfig(
    Ja4Config ja4, Sp1FingerprintConfig sp1, int sampleHeadersInterval) {

  public static final FingerprintConfig DEFAULT = FingerprintConfig.builder().build();

  /// Builder with the defaults preset
  public static Builder builder() {
    return new Builder().ja4(Ja4Config.DEFAULT).sp1(Sp1FingerprintConfig.DEFAULT);
  }

  /// Completed by Lombok, which generates everything but [#build()]
  public static class Builder {

    /// @throws IllegalArgumentException if `sampleHeadersInterval` is negative
    public FingerprintConfig build() {
      if (sampleHeadersInterval < 0) {
        throw new IllegalArgumentException(
            "fingerprint.sampleHeadersInterval must not be negative");
      }
      return new FingerprintConfig(ja4, sp1, sampleHeadersInterval);
    }
  }
}
