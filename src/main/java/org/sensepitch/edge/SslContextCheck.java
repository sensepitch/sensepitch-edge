package org.sensepitch.edge;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/// Checks that a server [SslContext] can complete a TLS handshake, before [SslSetup] uses it for
/// connections.
///
/// With Netty 4.2.21 and [SslProvider#OPENSSL], [SslContextBuilder#build()] accepts a private
/// key that does not belong to the certificate. The server uses the key during the handshake.
/// If a reload sees the renewed certificate before the matching key is written, it could apply
/// the pair and every handshake would fail. The check uses the server context to sign a handshake.
/// The client verifies the signature against the certificate's public key. This exercises the
/// configured provider with RSA and EC keys without separate private key parsing.
///
/// The client trusts any certificate: it checks the key and certificate pair only, not the chain
/// or the host name.
///
/// Other servers check the pair when loading it. The implementations reviewed on 2026-11-26 use
/// direct checks:
///
/// - NGINX handles a key mismatch reported by OpenSSL when it loads the key.
///   [NGINX HTTPS setup](https://nginx.org/en/docs/http/configuring_https_servers.html)
/// - Apache httpd calls `SSL_CTX_check_private_key()` during TLS setup.
///   [Source](https://raw.githubusercontent.com/apache/httpd/trunk/modules/ssl/ssl_engine_init.c)
/// - Caddy's CertMagic calls Go's `tls.X509KeyPair()`, which compares the public keys.
///   [CertMagic source](https://github.com/caddyserver/certmagic/blob/master/certificates.go)
///   and [Go TLS source](https://go.dev/src/crypto/tls/tls.go#L318).
///
/// @author Jens Wilke
final class SslContextCheck {

  /// Maximum in-memory roundtrips, each with one client-to-server transfer and one return transfer.
  /// Measured on 2026-11-26 with Netty 4.2.21 and the RSA test certificate without a chain:
  /// TLS 1.3 completes in 5 roundtrips and TLS 1.2 in 9. An unfinished handshake at the limit
  /// fails the check.
  private static final int MAX_HANDSHAKE_ROUNDTRIPS = 10;

  /// What the engines wrap: a handshake carries no application data
  private static final ByteBuffer NO_APPLICATION_DATA = ByteBuffer.allocate(0);

  private SslContextCheck() {}

  /// @throws SSLException if the handshake fails, e.g. the key does not belong to the certificate
  static void requireHandshake(SslContext server) throws SSLException {
    requireHandshake(server, MAX_HANDSHAKE_ROUNDTRIPS);
  }

  /// @param maxRoundtrips number of roundtrips allowed, [#MAX_HANDSHAKE_ROUNDTRIPS] in production
  static void requireHandshake(SslContext server, int maxRoundtrips) throws SSLException {
    requireHandshake(server, buildClient(), maxRoundtrips);
  }

  /// @param client the client side, a test offers a single protocol or cipher suite with it
  static void requireHandshake(SslContext server, SslContext client, int maxRoundtrips)
      throws SSLException {
    SSLEngine serverEngine = server.newEngine(ByteBufAllocator.DEFAULT);
    SSLEngine clientEngine = client.newEngine(ByteBufAllocator.DEFAULT);
    try {
      ByteBuffer toServer = ByteBuffer.allocate(clientEngine.getSession().getPacketBufferSize());
      ByteBuffer toClient = ByteBuffer.allocate(serverEngine.getSession().getPacketBufferSize());
      ByteBuffer applicationData =
          ByteBuffer.allocate(
              Math.max(
                  clientEngine.getSession().getApplicationBufferSize(),
                  serverEngine.getSession().getApplicationBufferSize()));
      clientEngine.beginHandshake();
      serverEngine.beginHandshake();
      for (int roundtrip = 0; roundtrip < maxRoundtrips; roundtrip++) {
        transfer(clientEngine, toServer, serverEngine, applicationData);
        transfer(serverEngine, toClient, clientEngine, applicationData);
        if (finished(clientEngine) && finished(serverEngine)) {
          return;
        }
      }
      throw new SSLException("TLS handshake exceeded the roundtrip limit of " + maxRoundtrips);
    } finally {
      ReferenceCountUtil.release(serverEngine);
      ReferenceCountUtil.release(clientEngine);
    }
  }

  /// The client side of the check, the same provider as the server, so its algorithm support
  /// decides, not the JDK's. Built per check, which only runs for a newly loaded certificate.
  private static SslContext buildClient() throws SSLException {
    return SslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .sslProvider(SslProvider.OPENSSL)
        .build();
  }

  /// Whether the engine needs no more handshake steps, ends the loop of [#requireHandshake]
  private static boolean finished(SSLEngine engine) {
    return engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
  }

  /// One direction of a handshake roundtrip: what `from` sends is passed to `to`. Records `to`
  /// does not consume stay in `records` for the next roundtrip. [#MAX_HANDSHAKE_ROUNDTRIPS] bounds
  /// the loop.
  ///
  /// @param records holds the TLS records not consumed by `to` yet, in write mode
  /// @param applicationData receives application data, there is none during a handshake
  private static void transfer(
      SSLEngine from, ByteBuffer records, SSLEngine to, ByteBuffer applicationData)
      throws SSLException {
    runDelegatedTasks(from);
    from.wrap(NO_APPLICATION_DATA, records);
    records.flip();
    applicationData.clear();
    to.unwrap(records, applicationData);
    runDelegatedTasks(to);
    records.compact();
  }

  /// Runs the tasks an engine hands out, e.g. a signature, on the calling thread, so the
  /// handshake in [#transfer] can go on
  private static void runDelegatedTasks(SSLEngine engine) {
    Runnable task;
    while ((task = engine.getDelegatedTask()) != null) {
      task.run();
    }
  }
}
