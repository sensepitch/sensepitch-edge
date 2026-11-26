package org.sensepitch.edge;

import com.maxmind.geoip2.DatabaseReader;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/// @author Jens Wilke
public class GeoIp2CountryLookup implements IpTraitsCollector {

  private final DatabaseReader reader;

  public GeoIp2CountryLookup(String countryDbFile) throws IOException {
    File database = new File(countryDbFile);
    reader = new DatabaseReader.Builder(database).build();
  }

  /// Sets the country, if the address is in the database and its network has one
  @Override
  public void collect(IpTraits.Builder builder, InetAddress address) throws Exception {
    // a network without a country has an empty country record, GeoIP2 (4.4.0) puts one in place
    // of a missing one, so its ISO code is null
    String country =
        reader.tryCountry(address).map(response -> response.getCountry().getIsoCode()).orElse(null);
    if (country != null) {
      builder.isoCountry(country);
    }
  }

  /// Closes the underlying database reader
  @Override
  public void close() throws IOException {
    reader.close();
  }
}
