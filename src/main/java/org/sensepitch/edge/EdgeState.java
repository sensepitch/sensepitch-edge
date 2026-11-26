package org.sensepitch.edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// Everything built from the configuration that a reload replaces: routing, known hosts,
/// certificates and the IP lookup. Immutable, [Proxy] swaps the complete state, and the handlers
/// read the current state once per request.
///
/// @param config the configuration the state was built from, with map keys injected
/// @param ssl the SSL contexts by SNI host and the loaded certificates, the next state takes over
///   the unchanged contexts
/// @param knownHosts hosts accepted by [SanitizeHostHandler], already filtered
/// @param upstreams the upstreams in use, handed to the next state to take over unchanged ones
/// @param ipLookupFiles the database files of the IP lookup, it is taken over by the next state
///   only if they are unchanged, see [ConfigFiles.Snapshot#unchanged()]
/// @param configId short hash of the masked configuration, logged with each request,
///   see [#configId(ProxyConfig)]
/// @author Jens Wilke
record EdgeState(
    ProxyConfig config,
    SiteSelector siteSelector,
    SslSetup ssl,
    Set<String> knownHosts,
    UnservicedHost unservicedHost,
    IpTraitsLookup ipTraitsLookup,
    ConfigFiles.Snapshot ipLookupFiles,
    UpstreamRegistry upstreams,
    String configId) {

  /// Length of the config id in hex characters, 64 bits
  static final int CONFIG_ID_LENGTH = 16;

  /// First 64 bits of SHA-256 over the UTF-8 masked configuration YAML, for the request log
  static String configId(ProxyConfig config) {
    byte[] digest = Digests.sha256(Proxy.toYaml(config).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest).substring(0, CONFIG_ID_LENGTH);
  }

  /// Builds and validates a complete state. Nothing of the previous state is modified or closed,
  /// so on an exception the previous state can stay active.
  ///
  /// @param previous the active state, or `null` at startup. Unchanged upstreams, SSL contexts
  ///   and an unchanged IP lookup are taken over from it.
  /// @param files reads the referenced files and records them, also those of a taken over IP
  ///   lookup
  static EdgeState build(
      ProxyContext ctx, ProxyConfig config, EdgeState previous, ConfigFiles files) {
    UpstreamRegistry upstreams =
        new UpstreamRegistry(ctx, previous != null ? previous.upstreams : null);
    SiteSelector siteSelector = new SiteSelector(ctx, config, upstreams, files);
    Set<String> servicedHosts = siteSelector.getServicedHosts();
    SslSetup ssl =
        SslSetup.build(
            config.listen(), servicedHosts, previous != null ? previous.ssl : null, files);
    UnservicedHostConfig unservicedHostConfig =
        config.unservicedHost() != null
            ? config.unservicedHost()
            : UnservicedHostConfig.builder().build();
    if (unservicedHostConfig.servicedDomains() == null) {
      unservicedHostConfig =
          unservicedHostConfig.toBuilder().servicedDomains(servicedHosts).build();
    }
    UnservicedHost unservicedHost = new UnservicedHost(unservicedHostConfig);
    Set<String> knownHosts = new HashSet<>(servicedHosts);
    if (config.listen().hosts() != null) {
      knownHosts.addAll(config.listen().hosts());
    }
    IpTraitsLookup ipTraitsLookup;
    ConfigFiles.Snapshot ipLookupFiles;
    if (previous != null
        && Objects.equals(previous.config.ipLookup(), config.ipLookup())
        && previous.ipLookupFiles.unchanged()) {
      ipTraitsLookup = previous.ipTraitsLookup;
      ipLookupFiles = previous.ipLookupFiles;
    } else {
      // separately, so the next state can tell whether the databases changed
      ConfigFiles ipFiles = new ConfigFiles();
      ipTraitsLookup = buildIpTraitsLookup(config.ipLookup(), ctx.metrics(), ipFiles);
      ipLookupFiles = ipFiles.snapshot();
    }
    files.recordAll(ipLookupFiles);
    return new EdgeState(
        config,
        siteSelector,
        ssl,
        SanitizeHostHandler.filterKnownHosts(knownHosts),
        unservicedHost,
        ipTraitsLookup,
        ipLookupFiles,
        upstreams,
        configId(config));
  }

  private static IpTraitsLookup buildIpTraitsLookup(
      IpLookupConfig cfg, ProxyMetrics metrics, ConfigFiles files) {
    if (cfg == null) {
      return IpTraitsLookup.NONE;
    }
    try {
      return CombinedIpTraitsLookup.create(cfg, metrics, files);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /// How long a replaced IP lookup stays open after the swap. A connection whose pipeline was
  /// built from the old state holds the old lookup and queries it in `channelActive`, see
  /// [IpTraitsHandler]. That happens right after the pipeline is built, so a few seconds cover
  /// it with a wide margin; the delay only postpones releasing the database readers, which is why
  /// it is a constant and no configuration key.
  static final long REPLACED_IP_LOOKUP_CLOSE_DELAY_MILLIS = 5_000;

  /// Releases what the next state did not take over. Called after the swap. A replaced IP lookup
  /// is closed after [#REPLACED_IP_LOOKUP_CLOSE_DELAY_MILLIS], so connections accepted just before
  /// the swap still get their traits. A close still pending when the proxy shuts down is dropped
  /// with the scheduler's tasks; the readers are then released when the process ends.
  ///
  /// @param scheduler runs the delayed close, the event loop group in production
  void closeReplacedBy(EdgeState next, ScheduledExecutorService scheduler) {
    next.upstreams.closeUnusedFrom(upstreams);
    if (next.ipTraitsLookup != ipTraitsLookup && ipTraitsLookup instanceof AutoCloseable c) {
      scheduler.schedule(
          () -> closeReplacedIpLookup(c),
          REPLACED_IP_LOOKUP_CLOSE_DELAY_MILLIS,
          TimeUnit.MILLISECONDS);
    }
  }

  /// The delayed close of [#closeReplacedBy], logs a failure so a reader left open is visible
  private static void closeReplacedIpLookup(AutoCloseable lookup) {
    try {
      lookup.close();
    } catch (Exception e) {
      ProxyLogger.get(EdgeState.class).error("Closing IP lookup failed", e);
    }
  }
}
