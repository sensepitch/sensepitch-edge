package org.sensepitch.edge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// Writes minimal IPv4 databases in the MaxMind DB format. For tests that need a database file
/// without shipping one and without a writer library.
///
/// The layout follows the MaxMind DB format specification 2.0: a search tree of one node with
/// 24-bit records both pointing to the data record, 16 zero bytes, the data section, the metadata
/// marker and the metadata map.
///
/// @see <a href="https://maxmind.github.io/MaxMind-DB/">MaxMind DB File Format Specification</a>
/// @author Jens Wilke
final class TinyMmdb {

  private static final int NODE_COUNT = 1;
  private static final int RECORD_SIZE = 24;

  /// A record value above the node count points into the data section, at value - nodes - 16
  private static final int DATA_RECORD = NODE_COUNT + 16;

  /// A record value equal to the node count means the network has no data
  private static final int NO_DATA_RECORD = NODE_COUNT;

  private static final byte[] METADATA_MARKER = {
    (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 'M', 'a', 'x', 'M', 'i', 'n', 'd', '.', 'c', 'o', 'm'
  };

  // control byte types, the type in the upper three bits
  private static final int STRING = 2;
  private static final int UINT16 = 5;
  private static final int UINT32 = 6;
  private static final int MAP = 7;

  // extended types, control byte type 0 and the type minus 7 in the next byte
  private static final int UINT64 = 9;
  private static final int ARRAY = 11;

  private TinyMmdb() {}

  /// Writes an IPinfo database that maps every IPv4 address to a flat `country_code`.
  static void writeCountry(Path file, String countryCode) throws IOException {
    write(file, ipInfoRecord(countryCode), "ipinfo");
  }

  /// Writes an IPinfo database that maps the lower half of the IPv4 space (`0.0.0.0/1`) to a flat
  /// record of `fields`, in their iteration order. The upper half (`128.0.0.0/1`) has no data.
  static void writeIpInfoLowerHalf(Path file, Map<String, String> fields) throws IOException {
    ByteArrayOutputStream record = new ByteArrayOutputStream();
    control(record, MAP, fields.size());
    fields.forEach(
        (key, value) -> {
          string(record, key);
          string(record, value);
        });
    write(file, DATA_RECORD, NO_DATA_RECORD, record.toByteArray(), "ipinfo");
  }

  /// Writes a GeoLite2-Country database that maps every IPv4 address to `country.iso_code`.
  static void writeGeoLite2Country(Path file, String countryCode) throws IOException {
    write(file, geoLite2Record(countryCode), "GeoLite2-Country");
  }

  private static byte[] ipInfoRecord(String countryCode) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    control(out, MAP, 1);
    string(out, "country_code");
    string(out, countryCode);
    return out.toByteArray();
  }

  private static byte[] geoLite2Record(String countryCode) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    control(out, MAP, 1);
    string(out, "country");
    control(out, MAP, 1);
    string(out, "iso_code");
    string(out, countryCode);
    return out.toByteArray();
  }

  private static void write(Path file, byte[] record, String databaseType) throws IOException {
    write(file, DATA_RECORD, DATA_RECORD, record, databaseType);
  }

  /// Writes a database with one data record; `left` and `right` are the records of the only node,
  /// for the addresses with the first bit 0 and 1, pointing to the data record or to no data
  private static void write(Path file, int left, int right, byte[] record, String databaseType)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    record24(out, left);
    record24(out, right);
    out.write(new byte[16]);
    out.writeBytes(record);
    out.write(METADATA_MARKER);
    control(out, MAP, 9);
    string(out, "node_count");
    control(out, UINT32, 1);
    out.write(NODE_COUNT);
    string(out, "record_size");
    control(out, UINT16, 1);
    out.write(RECORD_SIZE);
    string(out, "ip_version");
    control(out, UINT16, 1);
    out.write(4);
    string(out, "database_type");
    string(out, databaseType);
    string(out, "languages");
    extended(out, ARRAY, 1);
    string(out, "en");
    string(out, "binary_format_major_version");
    control(out, UINT16, 1);
    out.write(2);
    string(out, "binary_format_minor_version");
    control(out, UINT16, 0);
    string(out, "build_epoch");
    extended(out, UINT64, 0);
    string(out, "description");
    control(out, MAP, 1);
    string(out, "en");
    string(out, "test database");
    Files.write(file, out.toByteArray());
  }

  private static void record24(ByteArrayOutputStream out, int value) {
    out.write(value >> 16);
    out.write(value >> 8);
    out.write(value);
  }

  /// Control byte of a type with a size below 29, which needs no extra size bytes
  private static void control(ByteArrayOutputStream out, int type, int size) {
    out.write(type << 5 | size);
  }

  private static void extended(ByteArrayOutputStream out, int type, int size) {
    out.write(size);
    out.write(type - 7);
  }

  private static void string(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    control(out, STRING, bytes.length);
    out.writeBytes(bytes);
  }
}
