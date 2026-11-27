package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.sensepitch.edge.config.KeyInjector;
import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/// @author Jens Wilke
public class ConfigTest {

  @Test
  public void readConfigFromEnvironmentTextList() throws Exception {
    Map<String, String> env = Map.of("XX_TEXTS", "/xy,special,123");
    AllFieldTypesConfig cfg =
        (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertEquals("[/xy, special, 123]", cfg.texts().toString());
  }

  @Test
  public void readConfigFromEnvironmentTextListSeparate() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_TEXTS_0", "/xy",
            "XX_TEXTS_1", "special");
    AllFieldTypesConfig cfg =
        (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertEquals("[/xy, special]", cfg.texts().toString());
  }

  @Test
  public void readConfigFromEnvironmentForSingleObject() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_NUMBER", "123",
            "XX_LONG_NUMBER", "5000000000",
            "XX_FLAG", "true",
            "XX_TEXT", "hello world");
    AllFieldTypesConfig cfg =
        (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertEquals(123, cfg.number());
    assertEquals(5_000_000_000L, cfg.longNumber());
    assertEquals(true, cfg.flag());
    assertEquals("hello world", cfg.text());
  }

  @Test
  public void readConfigFromEnvironmentNestedObject() throws Exception {
    Map<String, String> env = Map.of("XX_ALL_NUMBER", "123");
    NestedTestConfig cfg =
        (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertNotNull(cfg.all());
    assertNull(cfg.list());
    assertEquals(false, cfg.enable());
    assertEquals(123, cfg.all().number());
  }

  @Test
  public void testReadConfigFromEnvironmentForMultipleObjects() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_LIST_0_FLAG", "true",
            "XX_LIST_1_NUMBER", "234");
    NestedTestConfig cfg =
        (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertNotNull(cfg.list());
    assertEquals(true, cfg.list().get(0).flag());
    assertEquals(false, cfg.list().get(1).flag());
    assertEquals(234, cfg.list().get(1).number());
  }

  @Test
  public void readMap() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_MAP_0_KEY", "hello",
            "XX_MAP_0_NUMBER", "234");
    NestedTestConfig cfg =
        (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertThat(cfg.map()).isNotNull();
    assertThat(cfg.map().get("hello").number()).isEqualTo(234);
  }

  @Test
  public void readAllFieldTypesFromYaml() {
    String yaml =
        """
      number: 123
      longNumber: 5000000000
      flag: true
      text: hello world
      texts:
        - one
        - two
      list:
        - number: 123
        - number: 456
      map:
        first:
          number: 1001
        second:
          number: 1002
    """;
    Yaml parser = new Yaml();
    Node root = parser.compose(new StringReader(yaml));
    // printNode(root, 2);
    AllFieldTypesConfig obj = RecordConstructor.construct(AllFieldTypesConfig.class, root);
    assertThat(obj.number()).isEqualTo(123);
    assertThat(obj.longNumber()).isEqualTo(5_000_000_000L);
    assertThat(obj.flag()).isEqualTo(true);
    assertThat(obj.text()).isEqualTo("hello world");
    assertThat(obj.texts()).hasSize(2).first().isEqualTo("one");
    assertThat(obj.list()).hasSize(2);
    assertThat(obj.list().getFirst().number()).isEqualTo(123);
    assertThat(obj.map().get("second").number()).isEqualTo(1002);
  }

  /// A map of plain strings is read as numbered `KEY` and `VALUE` pairs; a key without its value
  /// is rejected instead of being dropped
  @Test
  public void readStringMapFromEnvironment() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_LABELS_0_KEY", "Authorization",
            "XX_LABELS_0_VALUE", "Bearer secret",
            "XX_LABELS_1_KEY", "VL-Stream-Fields",
            "XX_LABELS_1_VALUE", "service.name");
    AllFieldTypesConfig cfg =
        (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertThat(cfg.labels())
        .containsExactly(
            Map.entry("Authorization", "Bearer secret"),
            Map.entry("VL-Stream-Fields", "service.name"));
    Map<String, String> noValue = Map.of("XX_LABELS_0_KEY", "Authorization");
    assertThatThrownBy(
            () -> EnvInjector.injectFromEnv("XX_", noValue, AllFieldTypesConfig.builder()))
        .hasMessage("Missing env var XX_LABELS_0_VALUE");
  }

  /// A value the record rejects fails the loading, with the message of the record
  @Test
  public void valueRejectedByTheRecordFailsTheLoading() {
    Node root = new Yaml().compose(new StringReader("number: -1\n"));
    assertThatThrownBy(() -> RecordConstructor.construct(AllFieldTypesConfig.class, root))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("number must be >= 0, was: -1");
  }

  @Test
  public void injectKey() {
    String yaml =
        """
      map:
        first:
          number: 1
        second:
          number: 2
    """;
    Yaml parser = new Yaml();
    Node root = parser.compose(new StringReader(yaml));
    // printNode(root, 2);
    AllFieldTypesConfig obj = RecordConstructor.construct(AllFieldTypesConfig.class, root);
    assertThat(obj.map().get("first").key()).isNull();
    obj = KeyInjector.injectAllMapKeys(obj);
    assertThat(obj.map().get("first").key()).isEqualTo("first");
    assertThat(obj.map().get("second").key()).isEqualTo("second");
  }

  /// A YAML key that looks like a number is a string map key, so it is injected as key
  @Test
  public void injectNumericYamlKey() {
    Node root = new Yaml().compose(new StringReader("map:\n  123:\n    number: 1\n"));
    AllFieldTypesConfig obj = RecordConstructor.construct(AllFieldTypesConfig.class, root);
    obj = KeyInjector.injectAllMapKeys(obj);
    assertThat(obj.map()).containsOnlyKeys("123");
    assertThat(obj.map().get("123").key()).isEqualTo("123");
  }

  /// A map entry without value stays `null`, there is no record to inject the key into
  @Test
  public void injectKeySkipsNullValue() {
    Map<String, TestWithKeyConfig> map = new LinkedHashMap<>();
    map.put("empty", null);
    AllFieldTypesConfig obj =
        KeyInjector.injectAllMapKeys(AllFieldTypesConfig.builder().map(map).build());
    assertThat(obj.map()).containsEntry("empty", null);
  }

  /// A map built in code with a key that is not a string is rejected, not left without key
  @Test
  public void injectKeyRejectsNonStringKey() {
    AllFieldTypesConfig obj = configWithRawMapKey(123);
    assertThatThrownBy(() -> KeyInjector.injectAllMapKeys(obj))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Map key of AllFieldTypesConfig.map must be a string, was: 123 (java.lang.Integer)");
  }

  @Test
  public void injectKeyRejectsNullKey() {
    AllFieldTypesConfig obj = configWithRawMapKey(null);
    assertThatThrownBy(() -> KeyInjector.injectAllMapKeys(obj))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Map key of AllFieldTypesConfig.map must be a string, was: null");
  }

  /// Bypasses the generic map type, like a caller with a raw or unchecked map would
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static AllFieldTypesConfig configWithRawMapKey(Object key) {
    Map raw = new LinkedHashMap<>();
    raw.put(key, TestWithKeyConfig.builder().number(1).build());
    return AllFieldTypesConfig.builder().map(raw).build();
  }

  /// Debugging aid, logs the YAML node tree
  void printNode(Node node, int indent) {
    var log = LogManager.getLogger(ConfigTest.class);
    String pad = " ".repeat(indent);
    if (node instanceof MappingNode m) {
      log.info(pad + "MappingNode:");
      for (NodeTuple tuple : m.getValue()) {
        printNode(tuple.getKeyNode(), indent + 2);
        printNode(tuple.getValueNode(), indent + 2);
      }
    } else if (node instanceof SequenceNode s) {
      log.info(pad + "SequenceNode:");
      for (Node item : s.getValue()) {
        printNode(item, indent + 2);
      }
    } else if (node instanceof ScalarNode sc) {
      log.info("{}ScalarNode: {} ({})", pad, sc.getValue(), sc.getTag());
    }
  }

  /// The connection pool was removed, a configuration still setting it is rejected instead of
  /// silently ignored.
  @Test
  public void removedConnectionPoolIsRejected() {
    String yaml =
        """
        upstreams:
          shop:
            target: shop:8080
            connectionPool:
              maxSize: 10
        """;
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> Main.parseConfig(yaml));
    assertThat(ex).hasMessageContaining("Unknown parameter in UpstreamConfig: connectionPool");
  }

  /// A nested record starts from its `DEFAULT`, so the YAML only needs the values that differ.
  @Test
  public void nestedRecordStartsFromDefault() {
    ProxyConfig cfg = Main.parseConfig("listen:\n  httpsPort: 8443\n");
    assertEquals(8443, cfg.listen().httpsPort());
    assertEquals(ListenConfig.DEFAULT.address(), cfg.listen().address());
    assertEquals(ListenConfig.DEFAULT_HTTP_PORT, cfg.listen().httpPort());
  }

  /// A map entry without value, like `www.example.com:` in the README, is an empty record.
  @Test
  public void emptyMapValueIsEmptyRecord() {
    ProxyConfig cfg = Main.parseConfig("sites:\n  www.example.com:\n");
    assertNotNull(cfg.sites().get("www.example.com"));
    assertNull(cfg.sites().get("www.example.com").upstream());
  }

  @Test
  public void listExpected() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Main.parseConfig("listen:\n  hosts:\n    a: b\n"));
    assertThat(ex).hasMessageContaining("Sequence expected");
  }

  @Test
  public void mapExpected() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> Main.parseConfig("sites:\n  - a\n"));
    assertThat(ex).hasMessageContaining("Mapping expected");
  }

  @Test
  public void documentMustBeMapping() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> Main.parseConfig("just text"));
    assertThat(ex).hasMessageContaining("Cannot construct record of type");
  }

  /// The message names the line, not the value, which may be a secret
  @Test
  public void scalarForRecordNamesTheLineNotTheValue() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> Main.parseConfig("listen:\n  ssl: top-secret\n"));
    assertThat(assertThrows(IllegalArgumentException.class, () -> Main.parseConfig("")))
        .hasMessageEndingWith("a mapping is expected, found nothing");
    assertThat(ex)
        .hasMessageContaining(
            SslConfig.class.getSimpleName() + ": a mapping is expected, found a scalar in line 2")
        .hasMessageNotContaining("top-secret");
  }

  @Test
  public void environmentWithUnsupportedType() {
    Map<String, String> env = Map.of("XX_RATIO", "0.5");
    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> EnvInjector.injectFromEnv("XX_", env, EnvErrorsConfig.builder()));
    assertThat(ex).hasStackTraceContaining("Unsupported type double");
  }

  /// A numbered map entry of records needs its `KEY` variable, like a string map
  @Test
  public void environmentMapEntryWithoutKeyIsRejected() {
    Map<String, String> env = Map.of("XX_MAP_0_NUMBER", "234");
    assertThatThrownBy(() -> EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing env var XX_MAP_0_KEY");
  }

  @Test
  public void environmentMapValueNeedsKey() {
    Map<String, String> env = Map.of("XX_NO_KEYS_0_NUMBER", "1");
    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> EnvInjector.injectFromEnv("XX_", env, EnvErrorsConfig.builder()));
    assertThat(ex).hasStackTraceContaining("Map value needs to have key");
  }
}
