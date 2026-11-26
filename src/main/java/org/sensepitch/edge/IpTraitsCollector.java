package org.sensepitch.edge;

import java.net.InetAddress;

/// Adds traits found for an address to a shared builder. A collector backed by a database reader
/// releases it in [#close()], called when a reload replaces the lookup, see
/// [CombinedIpTraitsLookup#close()]; a collector without resources keeps the no-op default.
@FunctionalInterface
public interface IpTraitsCollector extends AutoCloseable {

  void collect(IpTraits.Builder builder, InetAddress address) throws Exception;

  /// Releases the resources of the collector, the default has none
  @Override
  default void close() throws Exception {}
}
