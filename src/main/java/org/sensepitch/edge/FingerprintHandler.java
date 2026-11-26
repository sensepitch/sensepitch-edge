package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/// Takes the request headers as the client sent them, before any other handler changes them: the
/// header names for every request, all headers for a sampled one, and the [Sp1Fingerprint] for
/// the upstream, as configured in [FingerprintConfig].
///
/// The edge changes the order of the headers: Netty's `HttpHeaders.set` removes a header and
/// appends it, so the sanitized `Host` would end up last and in lower case. That is why this
/// handler sits right after the [StripEdgeHeadersHandler], which only removes headers.
///
/// The taken headers are handed to the [RequestLoggingHandler] in a channel attribute that it
/// takes in the same `channelRead` of the request, so a following request cannot overwrite them.
@ChannelHandler.Sharable
public class FingerprintHandler extends ChannelInboundHandlerAdapter {

  public static final String SP1_HEADER = SensepitchHeaders.PREFIX + "Fingerprint-Sp1";
  public static final String SP1_RAW_HEADER = SensepitchHeaders.PREFIX + "Fingerprint-Sp1-Raw";

  /// Request headers left out of the sampled headers: secrets, values that change per request,
  /// and the forwarding headers, which tell nothing about the client software. `X-Forwarded-For`
  /// would also leak an address past [RequestLogDestinationConfig#anonymizeClientIp()]. The
  /// client's `Sp-*` headers are already stripped. Case insensitive.
  static final Set<String> SAMPLE_HEADERS_EXCLUDED = sampleHeadersExcluded();

  private static Set<String> sampleHeadersExcluded() {
    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    names.addAll(
        List.of(
            HttpHeaderNames.COOKIE.toString(),
            HttpHeaderNames.AUTHORIZATION.toString(),
            HttpHeaderNames.PROXY_AUTHORIZATION.toString(),
            TraceContext.TRACEPARENT,
            "X-Forwarded-For",
            "X-Forwarded-Proto"));
    return names;
  }

  /// The headers of the current request as the client sent them
  ///
  /// @param names the header names in order and spelling, see [RequestLogInfo#clientHeaderNames()]
  /// @param sample the headers as `Name: value`, `null` if the request is not sampled, see
  ///   [RequestLogInfo#sampleHeaders()]
  record ClientHeaders(List<CharSequence> names, List<String> sample) {

    /// Before a request was received
    static final ClientHeaders NONE = new ClientHeaders(List.of(), null);
  }

  static final AttributeKey<ClientHeaders> CLIENT_HEADERS =
      AttributeKey.valueOf(FingerprintHandler.class.getName() + ".clientHeaders");

  private final Supplier<FingerprintConfig> configs;

  /// @param configs supplies the active configuration, read per request so a reload applies
  public FingerprintHandler(Supplier<FingerprintConfig> configs) {
    this.configs = configs;
  }

  /// The headers of the current request as the client sent them, removed from the channel, so
  /// they are taken once per request
  ///
  /// @return the headers, or [ClientHeaders#NONE] if the handler did not see a request
  static ClientHeaders takeClientHeaders(Channel channel) {
    ClientHeaders headers = channel.attr(CLIENT_HEADERS).getAndSet(null);
    return headers != null ? headers : ClientHeaders.NONE;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      FingerprintConfig config = configs.get();
      HttpHeaders headers = request.headers();
      List<CharSequence> names = namesOf(headers);
      int interval = config.sampleHeadersInterval();
      List<String> sample =
          interval > 0 && ThreadLocalRandom.current().nextInt(interval) == 0
              ? sampleOf(headers)
              : null;
      ctx.channel().attr(CLIENT_HEADERS).set(new ClientHeaders(names, sample));
      if (config.sp1().enable()) {
        Sp1Fingerprint fingerprint = Sp1Fingerprint.of(names, headers);
        headers.set(SP1_HEADER, fingerprint.value());
        if (config.sp1().raw()) {
          headers.set(SP1_RAW_HEADER, fingerprint.raw());
        }
      }
    }
    super.channelRead(ctx, msg);
  }

  /// The names in order, the immutable `CharSequence` keys of the headers are not copied
  private static List<CharSequence> namesOf(HttpHeaders headers) {
    CharSequence[] names = new CharSequence[headers.size()];
    int count = 0;
    Iterator<Map.Entry<CharSequence, CharSequence>> entries = headers.iteratorCharSequence();
    while (entries.hasNext()) {
      names[count++] = entries.next().getKey();
    }
    return List.of(names);
  }

  /// The headers as `Name: value` without [#SAMPLE_HEADERS_EXCLUDED], allocates, but only for the
  /// sampled requests
  private static List<String> sampleOf(HttpHeaders headers) {
    List<String> sample = new ArrayList<>(headers.size());
    Iterator<Map.Entry<CharSequence, CharSequence>> entries = headers.iteratorCharSequence();
    while (entries.hasNext()) {
      Map.Entry<CharSequence, CharSequence> entry = entries.next();
      String name = entry.getKey().toString();
      if (!SAMPLE_HEADERS_EXCLUDED.contains(name)) {
        sample.add(name + ": " + entry.getValue());
      }
    }
    return List.copyOf(sample);
  }
}
