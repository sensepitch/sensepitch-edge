package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// What goes into the [Sp1Fingerprint]: the names as sent, without the context headers, and the
/// values of the invariant headers only.
class Sp1FingerprintTest {

  /// A navigation of a Chromium based browser, shortened
  static HttpHeaders browserHeaders() {
    return browserHeaders("Mozilla/5.0 Chrome/142", "de-DE");
  }

  /// Built in order, since `HttpHeaders.set` would move a header to the end
  static HttpHeaders browserHeaders(String userAgent, String acceptLanguage) {
    return new DefaultHttpHeaders()
        .add("Host", "example.com")
        .add("sec-ch-ua", "\"Chromium\";v=\"142\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Linux\"")
        .add("Upgrade-Insecure-Requests", "1")
        .add("User-Agent", userAgent)
        .add("Accept", "text/html")
        .add("Accept-Encoding", "gzip, br")
        .add("Accept-Language", acceptLanguage);
  }

  static Sp1Fingerprint fingerprintOf(HttpHeaders headers) {
    return Sp1Fingerprint.of(namesOf(headers), headers);
  }

  static List<CharSequence> namesOf(HttpHeaders headers) {
    List<CharSequence> names = new ArrayList<>();
    headers.iteratorCharSequence().forEachRemaining(entry -> names.add(entry.getKey()));
    return names;
  }

  @Test
  void namesInClientOrderThenInvariantValues() {
    Sp1Fingerprint fingerprint = fingerprintOf(browserHeaders());
    assertThat(fingerprint.raw())
        .isEqualTo(
            "Host,sec-ch-ua,sec-ch-ua-mobile,sec-ch-ua-platform,Upgrade-Insecure-Requests,"
                + "User-Agent,Accept,Accept-Encoding,Accept-Language"
                + "|accept=text/html;accept-encoding=gzip, br;sec-ch-ua=\"Chromium\";v=\"142\";"
                + "sec-ch-ua-mobile=?0;sec-ch-ua-platform=\"Linux\";upgrade-insecure-requests=1");
    assertThat(fingerprint.value())
        .hasSize(Ja4Fingerprint.HASH_LENGTH)
        .isEqualTo(Ja4Fingerprint.truncatedHash(fingerprint.raw()));
  }

  /// Values of the person and of the request, and the claimed agent, are not part of it
  @Test
  void sameForAnotherPersonAndRequestWithTheSameBrowser() {
    HttpHeaders other =
        browserHeaders("Mozilla/5.0 Chrome/999", "fr-FR")
            .add("Cookie", "session=1")
            .add("Referer", "https://example.com/")
            .add("If-None-Match", "\"etag\"")
            .add("Cache-Control", "max-age=0");
    assertThat(fingerprintOf(other)).isEqualTo(fingerprintOf(browserHeaders()));
  }

  @Test
  void spellingOrderAndInvariantValuesCount() {
    Sp1Fingerprint browser = fingerprintOf(browserHeaders());
    HttpHeaders lowerCaseHost = browserHeaders().remove("Host");
    lowerCaseHost = new DefaultHttpHeaders().add("host", "example.com").add(lowerCaseHost);
    HttpHeaders hostLast = browserHeaders().remove("Host").add("Host", "example.com");
    // the same names, the values are read from the headers
    Sp1Fingerprint otherEncoding =
        Sp1Fingerprint.of(
            namesOf(browserHeaders()), browserHeaders().set("Accept-Encoding", "identity"));
    assertThat(fingerprintOf(lowerCaseHost)).isNotEqualTo(browser);
    assertThat(fingerprintOf(hostLast)).isNotEqualTo(browser);
    assertThat(otherEncoding).isNotEqualTo(browser);
  }

  @Test
  void withoutInvariantHeadersOnlyTheNames() {
    HttpHeaders curl =
        new DefaultHttpHeaders().add("Host", "example.com").add("User-Agent", "curl/8");
    assertThat(fingerprintOf(curl).raw()).isEqualTo("Host,User-Agent|");
  }

  @Test
  void contextHeadersIgnoreCase() {
    assertThat(Sp1Fingerprint.isContextHeader("cookie")).isTrue();
    assertThat(Sp1Fingerprint.isContextHeader("IF-MODIFIED-SINCE")).isTrue();
    assertThat(Sp1Fingerprint.isContextHeader("If")).isFalse();
    assertThat(Sp1Fingerprint.isContextHeader("Accept")).isFalse();
  }
}
