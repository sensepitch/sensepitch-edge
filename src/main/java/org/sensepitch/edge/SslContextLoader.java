package org.sensepitch.edge;

import io.netty.handler.ssl.SslContext;
import java.util.HashMap;
import java.util.Map;

/// Loads the SSL contexts of one state build. A context of the previous state is taken over if
/// its [SslConfig] is equal and its key and certificate files are unchanged, see
/// [ConfigFiles.Snapshot#unchanged()], like the IP lookup in [EdgeState#build]: building an
/// OpenSSL context costs about 15 ms, and most reloads do not touch the certificates. A changed
/// file, e.g. a renewed certificate, is loaded again.
///
/// @author Jens Wilke
final class SslContextLoader {

  /// A loaded context with the description of its certificate and the files it was read from
  ///
  /// @param description see [Certificates#describe(SslConfig, ConfigFiles)]
  record Loaded(SslContext context, String description, ConfigFiles.Snapshot files) {}

  private final Map<SslConfig, Loaded> previous;
  private final ConfigFiles files;
  private final Map<SslConfig, Loaded> loaded = new HashMap<>();

  /// @param previous the contexts of the active state, empty at startup
  /// @param files the files of the build, receives the files of every context, also of one taken
  ///   over or one that failed to load
  SslContextLoader(Map<SslConfig, Loaded> previous, ConfigFiles files) {
    this.previous = previous;
    this.files = files;
  }

  /// The context for a configuration, taken over, already loaded in this build, or loaded now
  Loaded load(SslConfig cfg) {
    Loaded context = loaded.get(cfg);
    if (context != null) {
      return context;
    }
    context = previous.get(cfg);
    if (context == null || !context.files().unchanged()) {
      context = loadNew(cfg);
    }
    files.recordAll(context.files());
    loaded.put(cfg, context);
    return context;
  }

  /// Reads the files into a separate [ConfigFiles], so the next build can tell whether they
  /// changed. A failed read is recorded in the build's files anyway, so a rejected configuration
  /// is tried again when the missing file appears, see [Proxy#referencedFiles()].
  private Loaded loadNew(SslConfig cfg) {
    ConfigFiles own = new ConfigFiles();
    try {
      return new Loaded(
          Proxy.createSslContext(cfg, own), Certificates.describe(cfg, own), own.snapshot());
    } finally {
      files.recordAll(own.snapshot());
    }
  }

  /// The contexts of this build, for the next one
  Map<SslConfig, Loaded> loaded() {
    return Map.copyOf(loaded);
  }
}
