package org.sensepitch.edge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Writes minimal IPv4 databases in the MaxMind DB format. For tests that need a database file
/// without shipping one and without a writer library.
///
/// The layout follows the MaxMind DB format specification 2.0: a search tree of 24-bit records,
/// 16 zero bytes, the data section, the metadata marker and the metadata map. The tree is one node
/// for a database that maps every address to one record, or three nodes for one record per
/// quarter of the address space.
///
/// @see <a href="https://maxmind.github.io/MaxMind-DB/">MaxMind DB File Format Specification</a>
/// @author Jens Wilke
final class TinyMmdb {

  private static final int RECORD_SIZE = 24;

  /// Bytes between the search tree and the data section
  private static final int DATA_SECTION_SEPARATOR = 16;

  /// The quarters of the IPv4 space, selected by the first two address bits
  static final int QUARTERS = 4;

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
    writeEverywhere(file, ipInfoRecord(Map.of("country_code", countryCode)), "ipinfo");
  }

  /// Writes an IPinfo database with one flat record of string fields per quarter of the IPv4
  /// space: entry `i` for the addresses whose first two bits are `i`, i.e. `0.0.0.0/2`,
  /// `64.0.0.0/2`, `128.0.0.0/2` and `192.0.0.0/2`. A `null` entry means no data, the lookup
  /// finds nothing.
  static void writeIpInfoQuarters(Path file, List<Map<String, String>> quarters)
      throws IOException {
    if (quarters.size() != QUARTERS) {
      throw new IllegalArgumentException("one entry per quarter expected: " + quarters);
    }
    // the root splits on the first bit into node 1 and 2, those split on the second bit
    int nodeCount = 3;
    int[] tree = {1, 2, 0, 0, 0, 0};
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (int i = 0; i < QUARTERS; i++) {
      Map<String, String> fields = quarters.get(i);
      if (fields == null) {
        tree[2 + i] = noData(nodeCount);
      } else {
        tree[2 + i] = dataPointer(nodeCount, data.size());
        data.writeBytes(ipInfoRecord(fields));
      }
    }
    write(file, nodeCount, tree, data.toByteArray(), "ipinfo");
  }

  /// Writes a GeoLite2-Country database that maps every IPv4 address to `country.iso_code`.
  static void writeGeoLite2Country(Path file, String countryCode) throws IOException {
    writeEverywhere(file, geoLite2Record(countryCode), "GeoLite2-Country");
  }

  /// A flat map of strings, the record layout of IPinfo
  private static byte[] ipInfoRecord(Map<String, String> fields) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    control(out, MAP, fields.size());
    fields.forEach(
        (key, value) -> {
          string(out, key);
          string(out, value);
        });
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

  /// Writes a database of one node whose both records point to the only data record
  private static void writeEverywhere(Path file, byte[] record, String databaseType)
      throws IOException {
    int nodeCount = 1;
    int pointer = dataPointer(nodeCount, 0);
    write(file, nodeCount, new int[] {pointer, pointer}, record, databaseType);
  }

  /// The record value pointing to `offset` in the data section
  private static int dataPointer(int nodeCount, int offset) {
    return nodeCount + DATA_SECTION_SEPARATOR + offset;
  }

  /// The record value for a network without data
  private static int noData(int nodeCount) {
    return nodeCount;
  }

  /// @param tree the left and right record of each node, a value below `nodeCount` is the next
  ///   node, see [#dataPointer] and [#noData] for the others
  private static void write(Path file, int nodeCount, int[] tree, byte[] data, String databaseType)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int record : tree) {
      record24(out, record);
    }
    out.write(new byte[DATA_SECTION_SEPARATOR]);
    out.writeBytes(data);
    out.write(METADATA_MARKER);
    control(out, MAP, 9);
    string(out, "node_count");
    control(out, UINT32, 1);
    out.write(nodeCount);
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
