package org.sensepitch.edge;

import io.netty.handler.ssl.CipherSuiteFilter;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProtocols;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/// The TLS setup of one [EdgeState]: the SSL context for each SNI host name, built from the
/// [ListenConfig], and the description of the loaded certificates for the log.
///
/// A build takes over a context of the previous setup if its [SslConfig] is equal and its key and
/// certificate files are unchanged, see [ConfigFiles.Snapshot#unchanged()], like the IP lookup in
/// [EdgeState#build]: building an OpenSSL context costs about 15 ms, its check by [SslContextCheck]
/// about 2 ms more, and most reloads do not touch the certificates. A changed file, e.g. a renewed
/// certificate, is loaded again. Within one build, an equal [SslConfig] shares its context.
///
/// @author Jens Wilke
final class SslSetup implements Mapping<String, SslContext> {

  /// Pseudo host of the configured default certificate in the certificate log. The underscore is
  /// not legal in a host name, so it never collides with a real one, see
  /// [SanitizeHostHandler#UNKNOWN_HOST].
  static final String DEFAULT_HOST = "default_host";

  /// The cipher suites of the client-facing listener: a rule over the suites the TLS library
  /// supports, not a list, see [#forwardSecretAead]. `SslSetupTest` pins the outcome in a known
  /// list, so a suite a library upgrade adds or drops fails the test and is reviewed before it
  /// ships. Decision and alternatives in `doc/design/encrypted-transport.md`.
  static final CipherSuiteFilter CIPHER_SUITES = SslSetup::forwardSecretAead;

  /// TLS 1.2 suites with an ephemeral key exchange authenticated by the certificate, and an
  /// AEAD cipher. This is the TLS 1.2 part of the Mozilla server side TLS "intermediate" profile
  /// without DHE, which BoringSSL does not offer. It leaves out the suites Netty 4.2.21 enables
  /// by default without forward secrecy (`TLS_RSA_*`) and with CBC and SHA-1, and the PSK suites
  /// BoringSSL supports.
  private static final Pattern TLS_1_2_SUITE =
      Pattern.compile("TLS_ECDHE_(ECDSA|RSA)_WITH_(AES_(128|256)_GCM|CHACHA20_POLY1305)_SHA\\d+");

  /// TLS 1.3 suites. They have to be in the list, without one Netty disables TLS 1.3; BoringSSL
  /// ignores the selection and enables its own TLS 1.3 suites.
  private static final Pattern TLS_1_3_SUITE =
      Pattern.compile("TLS_(AES_(128|256)_GCM|CHACHA20_POLY1305)_SHA\\d+");

  /// The TLS context by SNI host name, with the wildcards and the fallback, built once per setup
  /// because it is queried on every handshake
  private final Mapping<String, SslContext> hostToContext;

  /// The bundle by SNI host or wildcard pattern, in configuration order: Let's Encrypt hosts,
  /// then SNI entries
  private final Map<String, CertificateBundle> bundlesByHost;

  /// The bundle of the configured default certificate, `null` if none is configured; the SNI
  /// fallback is the first host's bundle then. Read by the certificate log.
  private final CertificateBundle defaultBundle;

  /// The bundles of this setup by configuration, for the next build to take over
  private final Map<SslConfig, CertificateBundle> bundlesByConfig;

  private SslSetup(
      Map<String, CertificateBundle> bundlesByHost,
      CertificateBundle defaultBundle,
      SslContext fallback,
      Map<SslConfig, CertificateBundle> bundlesByConfig) {
    this.hostToContext = hostToContext(bundlesByHost, fallback);
    this.bundlesByHost = Map.copyOf(bundlesByHost);
    this.defaultBundle = defaultBundle;
    this.bundlesByConfig = Map.copyOf(bundlesByConfig);
  }

  // building

  /// Builds the contexts for the Let's Encrypt hosts, the SNI entries and the default.
  ///
  /// @param servicedHosts the hosts of the sites, with Let's Encrypt each gets its certificate
  /// @param previous the setup of the active state, or `null` at startup
  /// @param files the files of the state build, receives the files of every context, also of
  ///   one taken over or one that failed to load
  /// @throws IllegalArgumentException if no context is configured, or a file cannot be read
  static SslSetup build(
      ListenConfig cfg, Set<String> servicedHosts, SslSetup previous, ConfigFiles files) {
    Loader loader = new Loader(previous != null ? previous.bundlesByConfig : Map.of(), files);
    var knownHosts = new HashSet<>(servicedHosts);
    if (cfg.hosts() != null) {
      knownHosts.addAll(cfg.hosts());
    }
    var bundlesByHost = new LinkedHashMap<String, CertificateBundle>();
    if (cfg.letsEncrypt()) {
      for (String host : knownHosts) {
        bundlesByHost.put(host, loader.bundleFor(cfg.letsEncryptSsl(host)));
      }
    }
    if (cfg.snis() != null) {
      for (SniConfig sni : cfg.snis()) {
        SanitizeHostHandler.requireValidWildcard(sni.host());
        bundlesByHost.put(sni.host(), loader.bundleFor(sni.ssl()));
      }
    }
    CertificateBundle defaultBundle = cfg.ssl() != null ? loader.bundleFor(cfg.ssl()) : null;
    CertificateBundle fallback = defaultBundle;
    if (fallback == null && !bundlesByHost.isEmpty()) {
      fallback = bundlesByHost.sequencedValues().getFirst();
    }
    if (fallback == null) {
      throw new IllegalArgumentException("SSL setup missing");
    }
    return new SslSetup(bundlesByHost, defaultBundle, fallback.context(), loader.bundlesByConfig);
  }

  /// Selects the context by host name, exact names before wildcards. Netty normalizes exact SNI
  /// names (including IDNs); a wildcard match is checked against the same DNS label rules as HTTP
  /// before its context is returned, otherwise the default is used.
  private static Mapping<String, SslContext> hostToContext(
      Map<String, CertificateBundle> bundlesByHost, SslContext fallback) {
    var builder = new DomainWildcardMappingBuilder<Mapping<String, SslContext>>(host -> fallback);
    bundlesByHost.forEach(
        (pattern, bundle) -> {
          SslContext context = bundle.context();
          if (pattern.startsWith("*.")) {
            builder.add(
                pattern, host -> SanitizeHostHandler.validWildcardLabel(host) ? context : fallback);
          } else {
            builder.add(pattern, host -> context);
          }
        });
    var patternToSelector = builder.build();
    return host -> patternToSelector.map(host).map(host);
  }

  // SNI selection

  /// @return the context for the SNI host name, the default if none matches or `host` is `null`
  @Override
  public SslContext map(String host) {
    return hostToContext.map(host);
  }

  // certificate log

  /// The loaded certificates, for the log at startup
  String certificateList() {
    return list(descriptionsByHost());
  }

  /// The certificates added, removed or changed since `previous`, for the log of a reload, or an
  /// empty string if nothing changed
  String certificateChanges(SslSetup previous) {
    return changes(previous.descriptionsByHost(), descriptionsByHost());
  }

  /// The description of each certificate by host, [#DEFAULT_HOST] for the configured default,
  /// for the log
  private Map<String, String> descriptionsByHost() {
    Map<String, String> descriptionsByHost = new HashMap<>();
    bundlesByHost.forEach((host, bundle) -> descriptionsByHost.put(host, bundle.description()));
    if (defaultBundle != null) {
      descriptionsByHost.put(DEFAULT_HOST, defaultBundle.description());
    }
    return descriptionsByHost;
  }

  /// The certificate for the log: subject, serial number and expiry of the first certificate in
  /// the PEM data, which is the server certificate in a chain like Let's Encrypt `fullchain.pem`,
  /// plus the file location. Parsed before the context is built, so an invalid certificate is
  /// rejected with its location.
  ///
  /// @throws IllegalArgumentException if the data holds no valid certificate
  static String describe(String certPath, byte[] pem) {
    try {
      X509Certificate cert =
          (X509Certificate)
              CertificateFactory.getInstance("X.509")
                  .generateCertificate(new ByteArrayInputStream(pem));
      return "subject="
          + cert.getSubjectX500Principal().getName()
          + ", serial="
          + cert.getSerialNumber().toString(16)
          + ", notAfter="
          + cert.getNotAfter().toInstant()
          + ", file="
          + certPath;
    } catch (CertificateException e) {
      throw new IllegalArgumentException("Invalid certificate: " + certPath, e);
    }
  }

  /// One line per host, sorted, each starting with a line break
  static String list(Map<String, String> descriptionsByHost) {
    StringBuilder sb = new StringBuilder();
    for (String host : new TreeSet<>(descriptionsByHost.keySet())) {
      sb.append("\n  ").append(host).append(": ").append(descriptionsByHost.get(host));
    }
    return sb.toString();
  }

  /// Lines for added, removed and changed certificates, from the descriptions by host, sorted by
  /// host, or an empty string if nothing changed
  static String changes(Map<String, String> previous, Map<String, String> current) {
    TreeSet<String> hosts = new TreeSet<>(previous.keySet());
    hosts.addAll(current.keySet());
    StringBuilder sb = new StringBuilder();
    for (String host : hosts) {
      String was = previous.get(host);
      String now = current.get(host);
      if (Objects.equals(was, now)) {
        continue;
      }
      if (was == null) {
        sb.append("\n  added ").append(host).append(": ").append(now);
      } else if (now == null) {
        sb.append("\n  removed ").append(host).append(": ").append(was);
      } else {
        sb.append("\n  changed ").append(host).append(": ").append(now);
        sb.append("\n    was: ").append(was);
      }
    }
    return sb.toString();
  }

  // loading a context

  /// A certificate with its key, as configured: the TLS context built from them, the description
  /// of the certificate for the log, and the files they were read from
  private record CertificateBundle(
      SslContext context, String description, ConfigFiles.Snapshot files) {}

  /// Bundles whose key and certificate are both class path resources, shared by all setups of
  /// the JVM. Exception to the rule against static caches in `AGENTS.md`, justified because:
  /// - a class path resource cannot change while the process runs, and [ConfigFiles] does not
  ///   record or watch it, so the bundle stays valid for the life of the JVM
  /// - the tests configure their certificates from the class path and build about 110 TLS
  ///   contexts per `./mvnw test`, about 15 ms each (measured 2026-11-26)
  /// - production configures certificate files, which are never cached here
  ///
  /// Bounded by the number of distinct class path certificates in the jar.
  private static final ConcurrentMap<SslConfig, CertificateBundle> CLASS_PATH_BUNDLES =
      new ConcurrentHashMap<>();

  /// Whether key and certificate are both class path resources, for [#CLASS_PATH_BUNDLES]
  private static boolean isClassPath(SslConfig cfg) {
    return cfg.certPath().startsWith(ConfigFiles.CLASSPATH_PREFIX)
        && cfg.keyPath().startsWith(ConfigFiles.CLASSPATH_PREFIX);
  }

  /// Finds the contexts of one build and records their files in the build's files
  private static final class Loader {

    private final Map<SslConfig, CertificateBundle> previous;
    private final ConfigFiles files;
    private final Map<SslConfig, CertificateBundle> bundlesByConfig = new HashMap<>();

    Loader(Map<SslConfig, CertificateBundle> previous, ConfigFiles files) {
      this.previous = previous;
      this.files = files;
    }

    /// The bundle for a configuration: already loaded in this build, taken over from the
    /// previous setup, shared from [#CLASS_PATH_BUNDLES], or loaded now. The files of the bundle
    /// end up in the build's files on every path: a taken over bundle records its snapshot, a new
    /// one reads through a [ConfigFiles#component()] of the build.
    CertificateBundle bundleFor(SslConfig cfg) {
      CertificateBundle bundle = bundlesByConfig.get(cfg);
      if (bundle != null) {
        return bundle;
      }
      bundle = previous.get(cfg);
      if (bundle != null && bundle.files().unchanged()) {
        files.recordAll(bundle.files());
      } else if (isClassPath(cfg)) {
        // class path resources are not recorded, there is nothing to record
        bundle = CLASS_PATH_BUNDLES.computeIfAbsent(cfg, c -> loadBundle(c, new ConfigFiles()));
      } else {
        bundle = loadBundle(cfg, files.component());
      }
      bundlesByConfig.put(cfg, bundle);
      return bundle;
    }
  }

  /// Reads key and certificate once and builds the bundle's context and description from the same
  /// bytes, for [Loader#bundleFor(SslConfig)]. Records nothing itself.
  ///
  /// @param read collects the files read for the bundle's snapshot, so the next build can tell
  ///   whether they changed
  private static CertificateBundle loadBundle(SslConfig cfg, ConfigFiles read) {
    byte[] cert = readFully(read, cfg.certPath());
    byte[] key = readFully(read, cfg.keyPath());
    String description = describe(cfg.certPath(), cert);
    return new CertificateBundle(createSslContext(cfg, cert, key), description, read.snapshot());
  }

  /// Reads a key or certificate location, for [#loadBundle(SslConfig, ConfigFiles)]
  ///
  /// @throws IllegalArgumentException if the location cannot be read
  private static byte[] readFully(ConfigFiles read, String location) {
    try (InputStream in = read.open(location)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read " + location, e);
    }
  }

  /// Builds an OpenSSL server context and checks it with a TLS handshake in memory, for
  /// [#loadBundle(SslConfig, ConfigFiles)]. OpenSSL accepts a key of another certificate when
  /// building, see [SslContextCheck]; a context taken over was checked when it was loaded.
  ///
  /// @throws IllegalArgumentException if OpenSSL rejects the key or certificate, or the key does
  ///   not belong to the certificate
  private static SslContext createSslContext(SslConfig cfg, byte[] cert, byte[] key) {
    try {
      SslContext context =
          SslContextBuilder.forServer(new ByteArrayInputStream(cert), new ByteArrayInputStream(key))
              .protocols(SslProtocols.TLS_v1_2, SslProtocols.TLS_v1_3)
              .ciphers(null, CIPHER_SUITES)
              .clientAuth(ClientAuth.NONE)
              .sslProvider(SslProvider.OPENSSL)
              .build();
      SslContextCheck.requireHandshake(context);
      return context;
    } catch (SSLException e) {
      throw new IllegalArgumentException(
          "Cannot build the TLS context from " + cfg.certPath() + " and " + cfg.keyPath(), e);
    }
  }

  /// Selects the suites of [#CIPHER_SUITES] from all the library supports, in the library's
  /// order. Netty's own default list is no base: it is fixed in Netty, has suites without
  /// forward secrecy and lacks ChaCha20 for TLS 1.2.
  ///
  /// @param requested `null`, the builder configures no list
  /// @param defaults Netty's default list, not used
  private static String[] forwardSecretAead(
      Iterable<String> requested, List<String> defaults, Set<String> supported) {
    return supported.stream()
        .filter(
            suite ->
                TLS_1_2_SUITE.matcher(suite).matches() || TLS_1_3_SUITE.matcher(suite).matches())
        .toArray(String[]::new);
  }
}
