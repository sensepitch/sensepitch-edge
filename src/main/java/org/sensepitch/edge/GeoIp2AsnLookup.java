package org.sensepitch.edge;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/// @author Jens Wilke
/// @see <a href="https://github.com/maxmind/GeoIP2-java">GeoIP2-java</a>
public class GeoIp2AsnLookup implements IpTraitsCollector {

  private final DatabaseReader reader;

  public GeoIp2AsnLookup(String asnDbFile) throws IOException {
    File database = new File(asnDbFile);
    reader = new DatabaseReader.Builder(database).build();
  }

  /// Sets the ASN, if the address is in the database
  @Override
  public void collect(IpTraits.Builder builder, InetAddress address) throws Exception {
    try {
      builder.asn(reader.asn(address).getAutonomousSystemNumber());
    } catch (AddressNotFoundException ex) {
      // not in the database, e.g. a private range: the ASN stays unset
    }
  }

  /// Closes the underlying database reader
  @Override
  public void close() throws IOException {
    reader.close();
  }
}
