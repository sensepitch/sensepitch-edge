package org.sensepitch.edge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// Finds the first public IPv4 address on an active, non-virtual host network interface.
///
/// @author Jens Wilke
public class PublicIpv4Finder {

  private PublicIpv4Finder() {}

  /// Returns the first IPv4 address that is:
  /// - not loopback (`127/8`)
  /// - not link-local (`169.254/16`)
  /// - not site-local (`10/8`, `172.16/12`, `192.168/16`)
  /// - on an interface that is up, not loopback, and not virtual
  public static Optional<Inet4Address> findFirstPublicIpv4() throws SocketException {
    return findFirstPublicIpv4(
        NetworkInterface.networkInterfaces(),
        NetworkInterface::isUp,
        NetworkInterface::isLoopback,
        NetworkInterface::isVirtual,
        NetworkInterface::inetAddresses);
  }

  /// Queries a boolean property of a network interface that may fail with [SocketException]
  @FunctionalInterface
  interface InterfaceFlag<T> {
    boolean test(T networkInterface) throws SocketException;
  }

  /// Finds the first public IPv4 address from `interfaces` using the given accessors, so tests can
  /// supply synthetic interfaces independent of the host NICs.
  static <T> Optional<Inet4Address> findFirstPublicIpv4(
      Stream<T> interfaces,
      InterfaceFlag<T> isUp,
      InterfaceFlag<T> isLoopback,
      Predicate<T> isVirtual,
      Function<T, Stream<InetAddress>> inetAddresses) {
    return interfaces
        .filter(networkInterface -> isUsable(networkInterface, isUp, isLoopback, isVirtual))
        .flatMap(inetAddresses)
        .filter(Inet4Address.class::isInstance)
        .map(Inet4Address.class::cast)
        .filter(PublicIpv4Finder::isPublic)
        .findFirst();
  }

  /// Skips down, loopback, virtual, or unreadable interfaces so one failing NIC does not abort
  /// discovery.
  private static <T> boolean isUsable(
      T networkInterface,
      InterfaceFlag<T> isUp,
      InterfaceFlag<T> isLoopback,
      Predicate<T> isVirtual) {
    try {
      return isUp.test(networkInterface)
          && !isLoopback.test(networkInterface)
          && !isVirtual.test(networkInterface);
    } catch (SocketException exception) {
      return false;
    }
  }

  /// Rejects loopback, link-local, and site-local IPv4 addresses that cannot identify the host on
  /// the public internet.
  private static boolean isPublic(Inet4Address address) {
    return !address.isLoopbackAddress()
        && !address.isLinkLocalAddress()
        && !address.isSiteLocalAddress();
  }
}
