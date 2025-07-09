package org.sensepitch.edge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Jens Wilke
 */
public class ConfigTest {

  @Test
  public void readConfigFromEnvironmentLists() throws Exception {
    Map<String, String> env = Map.of(
      "XX_TEXTS", "/xy,special,123"
    );
    AllFieldTypesConfig cfg =
      (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertEquals("[/xy, special, 123]", cfg.texts().toString());
  }

  @Test
  public void readConfigFromEnvironmentForSingleObject() throws Exception {
    Map<String, String> env = Map.of(
      "XX_NUMBER", "123",
      "XX_FLAG", "true",
      "XX_TEXT", "hello world"
    );
    AllFieldTypesConfig cfg =
      (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertEquals(123, cfg.number());
    assertEquals(true, cfg.flag());
    assertEquals("hello world", cfg.text());
  }


  @Test
  public void readConfigFromEnvironmentNestedObject() throws Exception {
    Map<String, String> env = Map.of(
      "XX_ALL_NUMBER", "123"
    );
    NestedTestConfig cfg =
      (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertNotNull(cfg.all());
    assertNull(cfg.list());
    assertEquals(false, cfg.enable());
    assertEquals(123, cfg.all().number());
  }

  @Test
  public void testReadConfigFromEnvironmentForMultipleObjects() throws Exception {
    Map<String, String> env = Map.of(
      "XX_LIST_0_FLAG", "true",
      "XX_LIST_1_NUMBER", "234"
    );
    NestedTestConfig cfg =
      (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertNotNull(cfg.list());
    assertEquals(true, cfg.list().get(0).flag());
    assertEquals(false, cfg.list().get(1).flag());
    assertEquals(234, cfg.list().get(1).number());
  }

}
