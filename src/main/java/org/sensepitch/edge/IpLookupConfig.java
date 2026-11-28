package org.sensepitch.edge;

import lombok.Builder;

/// @param geoIp2 MaxMind GeoIp2 databases for IP lookup
/// @param ipInfoPath IPInfo database for IP lookups, this database contains a combination of ASN
///   and country code. The free IPinfo lite dataset is more accurate than MaxMind Geolite and only
///   one database and lookup is needed. See [IPInfo lite](https://ipinfo.io/dashboard/lite).
/// @param knownCrawlers the crawler IP lists of the Sensepitch Known Crawlers release. Not set:
///   the crawler IP lists embedded in the edge are used, which go stale and will be removed, a
///   warning says so at startup.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record IpLookupConfig(
    GeoIp2Config geoIp2, String ipInfoPath, KnownCrawlersConfig knownCrawlers) {}
