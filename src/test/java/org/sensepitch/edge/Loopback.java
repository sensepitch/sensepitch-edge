package org.sensepitch.edge;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/// Ports and servers on the loopback interface, for tests that need a real socket: a log
/// server, an upstream or the proxy itself. Stop a server with
/// [TestingParameters#stop(HttpServer)].
final class Loopback {

  /// The loopback address, as a configuration value
  static final String ADDRESS = "127.0.0.1";

  private Loopback() {}

  /// An HTTP server on a port chosen by the OS, not started yet, so the test adds its contexts
  /// first
  static HttpServer httpServer() throws IOException {
    return HttpServer.create(new InetSocketAddress(ADDRESS, 0), 0);
  }

  /// A port that was free a moment ago, for a server that cannot bind port 0 and report it, e.g.
  /// the proxy configured by port. Probe it after the other servers of the test have their ports,
  /// or one of them could be given the port just released.
  static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }
}
