package org.sensepitch.edge;

import com.maxmind.geoip2.DatabaseReader;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/// @author Jens Wilke
public class GeoIp2CountryLookup implements CountryLookup {

  private final DatabaseReader reader;

  public GeoIp2CountryLookup(String countryDbFile) throws IOException {
    File database = new File(countryDbFile);
    reader = new DatabaseReader.Builder(database).build();
  }

  @Override
  public String lookupCountry(InetAddress addr) throws Exception {
    // a network without a country has an empty country record, GeoIP2 (4.4.0) puts one in place
    // of a missing one, so its ISO code is null
    return reader.tryCountry(addr).map(response -> response.getCountry().getIsoCode()).orElse(null);
  }
}
