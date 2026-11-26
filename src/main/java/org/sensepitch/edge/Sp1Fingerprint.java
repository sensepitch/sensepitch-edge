package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;
import java.util.List;

/// Fingerprint of the request headers of a browser build, version 1 (`sp1`), e.g.
/// `5b0c3f1d9a2e`, to detect a client that claims a browser in its `User-Agent` it is not. It
/// hashes, like the hashed parts of [Ja4Fingerprint]:
///
/// - the header names in the order and spelling the client sent them, without the
///   [#CONTEXT_HEADERS], whose presence depends on the request or the person, not on the browser
/// - the values of the [#INVARIANT_HEADERS], which are the same for every install of a browser
///   build
///
/// The `User-Agent` value is the claim under test and `Accept-Language` belongs to the person,
/// neither is hashed. The fingerprint differs per kind of request, a navigation sends other headers
/// than an image fetch. Why it is not JA4H, see `doc/design/browser-fingerprinting.md`.
///
/// The version is in the names: the log field `sensepitch.request.fingerprint.sp1`, the header
/// [FingerprintHandler#SP1_HEADER] and the config [FingerprintConfig#sp1()]. Any change of the
/// inputs, the context or invariant headers, the separators or the hash makes a new algorithm
/// `sp2` with names of its own; this one stays as it is, so its values remain comparable.
///
/// @param value the truncated hash of `raw`
/// @param raw the hash input: the names separated by `,`, then `|` and the invariant headers as
///   `name=value` separated by `;`
public record Sp1Fingerprint(String value, String raw) {

  /// Headers whose presence depends on the request or on the person: cookies and credentials,
  /// where the request came from, caching and partial requests, a body. `If-*` is matched by
  /// [#CONDITIONAL_PREFIX].
  static final List<AsciiString> CONTEXT_HEADERS =
      List.of(
          HttpHeaderNames.COOKIE,
          HttpHeaderNames.REFERER,
          HttpHeaderNames.AUTHORIZATION,
          HttpHeaderNames.ORIGIN,
          HttpHeaderNames.RANGE,
          HttpHeaderNames.CACHE_CONTROL,
          HttpHeaderNames.PRAGMA,
          HttpHeaderNames.CONTENT_LENGTH,
          HttpHeaderNames.CONTENT_TYPE);

  /// Prefix of the conditional request headers, e.g. `If-None-Match`
  static final String CONDITIONAL_PREFIX = "if-";

  /// Headers whose value is fixed by the browser build, in the order they are hashed
  static final List<AsciiString> INVARIANT_HEADERS =
      List.of(
          HttpHeaderNames.ACCEPT,
          HttpHeaderNames.ACCEPT_ENCODING,
          AsciiString.cached("sec-ch-ua"),
          AsciiString.cached("sec-ch-ua-mobile"),
          AsciiString.cached("sec-ch-ua-platform"),
          HttpHeaderNames.UPGRADE_INSECURE_REQUESTS);

  static final char NAME_SEPARATOR = ',';
  static final char PART_SEPARATOR = '|';
  static final char VALUE_SEPARATOR = ';';

  /// @param names the header names as the client sent them, see
  ///   [FingerprintHandler.ClientHeaders#names()]
  /// @param headers the headers, for the values of the invariant headers
  public static Sp1Fingerprint of(List<CharSequence> names, HttpHeaders headers) {
    StringBuilder raw = new StringBuilder();
    for (CharSequence name : names) {
      if (isContextHeader(name)) {
        continue;
      }
      if (!raw.isEmpty()) {
        raw.append(NAME_SEPARATOR);
      }
      raw.append(name);
    }
    raw.append(PART_SEPARATOR);
    boolean first = true;
    for (AsciiString name : INVARIANT_HEADERS) {
      String value = headers.get(name);
      if (value == null) {
        continue;
      }
      if (!first) {
        raw.append(VALUE_SEPARATOR);
      }
      first = false;
      raw.append(name).append('=').append(value);
    }
    String input = raw.toString();
    return new Sp1Fingerprint(Ja4Fingerprint.truncatedHash(input), input);
  }

  /// Whether the presence of the header depends on the request or the person, so it would split
  /// the fingerprint of one browser build
  static boolean isContextHeader(CharSequence name) {
    if (AsciiString.regionMatches(
        name, true, 0, CONDITIONAL_PREFIX, 0, CONDITIONAL_PREFIX.length())) {
      return true;
    }
    for (AsciiString contextHeader : CONTEXT_HEADERS) {
      if (contextHeader.contentEqualsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }
}
