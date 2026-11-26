package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// @author Jens Wilke
public class PublicIpv4FinderTest {

  private static final String PUBLIC_IPV4_FIRST = "198.51.100.10";
  private static final String PUBLIC_IPV4_SECOND = "203.0.113.20";

  private record FakeInterface(
      boolean up,
      boolean loopback,
      boolean virtual,
      boolean failOnUp,
      boolean failOnLoopback,
      List<InetAddress> addresses) {

    boolean isUp() throws SocketException {
      if (failOnUp) {
        throw new SocketException("isUp failed");
      }
      return up;
    }

    boolean isLoopback() throws SocketException {
      if (failOnLoopback) {
        throw new SocketException("isLoopback failed");
      }
      return loopback;
    }

    Stream<InetAddress> inetAddresses() {
      return addresses.stream();
    }
  }

  private static FakeInterface usable(String... literals) {
    return new FakeInterface(true, false, false, false, false, parse(literals));
  }

  private static List<InetAddress> parse(String... literals) {
    return Arrays.stream(literals).map(InetAddress::ofLiteral).toList();
  }

  private static Optional<Inet4Address> find(FakeInterface... interfaces) {
    return PublicIpv4Finder.findFirstPublicIpv4(
        Stream.of(interfaces),
        FakeInterface::isUp,
        FakeInterface::isLoopback,
        FakeInterface::virtual,
        FakeInterface::inetAddresses);
  }

  /// Skips down, loopback, virtual, and SocketException-throwing interfaces before picking a
  /// usable one.
  @Test
  public void skipsUnusableAndFailingInterfaces() {
    FakeInterface down = new FakeInterface(false, false, false, false, false, parse("192.0.2.1"));
    FakeInterface loopback = new FakeInterface(true, true, false, false, false, parse("192.0.2.2"));
    FakeInterface virtual = new FakeInterface(true, false, true, false, false, parse("192.0.2.3"));
    FakeInterface upThrows = new FakeInterface(true, false, false, true, false, parse("192.0.2.4"));
    FakeInterface loopbackThrows =
        new FakeInterface(true, false, false, false, true, parse("192.0.2.5"));
    FakeInterface active = usable(PUBLIC_IPV4_FIRST);

    assertThat(find(down, loopback, virtual, upThrows, loopbackThrows, active))
        .contains((Inet4Address) InetAddress.ofLiteral(PUBLIC_IPV4_FIRST));
  }

  /// Excludes IPv6, loopback, link-local, and site-local IPv4 addresses.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "2001:db8::1",
        "::1",
        "127.0.0.1",
        "169.254.1.1",
        "10.0.0.1",
        "172.16.0.1",
        "172.31.255.254",
        "192.168.1.1"
      })
  public void skipsNonPublicAndIpv6Addresses(String excludedLiteral) {
    assertThat(find(usable(excludedLiteral))).isEmpty();
    assertThat(find(usable(excludedLiteral, PUBLIC_IPV4_FIRST)))
        .contains((Inet4Address) InetAddress.ofLiteral(PUBLIC_IPV4_FIRST));
  }

  /// Picks the first public IPv4 address within one interface and across multiple usable
  /// interfaces.
  @Test
  public void selectsFirstPublicIpv4() {
    assertThat(find(usable(PUBLIC_IPV4_FIRST, PUBLIC_IPV4_SECOND)))
        .contains((Inet4Address) InetAddress.ofLiteral(PUBLIC_IPV4_FIRST));
    assertThat(
            find(
                usable(),
                usable("10.0.0.1", "2001:db8::1"),
                usable(PUBLIC_IPV4_FIRST),
                usable(PUBLIC_IPV4_SECOND)))
        .contains((Inet4Address) InetAddress.ofLiteral(PUBLIC_IPV4_FIRST));
  }

  /// Returns empty when no interfaces or no usable public IPv4 addresses are present.
  @Test
  public void returnsEmptyWhenNoPublicIpv4Matches() {
    assertThat(find()).isEmpty();
    assertThat(find(usable())).isEmpty();
    FakeInterface failing =
        new FakeInterface(true, false, false, true, false, parse(PUBLIC_IPV4_FIRST));
    assertThat(find(failing)).isEmpty();
  }

  /// Exercises the live host lookup and verifies any returned address is public IPv4.
  @Test
  public void findPublicIpv4() throws Exception {
    Optional<Inet4Address> found = PublicIpv4Finder.findFirstPublicIpv4();
    assertThat(found)
        .satisfies(
            optional ->
                optional.ifPresent(
                    address -> {
                      assertThat(address.isLoopbackAddress()).isFalse();
                      assertThat(address.isLinkLocalAddress()).isFalse();
                      assertThat(address.isSiteLocalAddress()).isFalse();
                    }));
  }
}
