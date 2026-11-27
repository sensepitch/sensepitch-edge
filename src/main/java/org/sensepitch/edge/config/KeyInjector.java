package org.sensepitch.edge.config;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.sensepitch.edge.HasKey;

/// If a configuration record has a key field, then the key is automatically assigned with the
/// mapping key. [HasKey] defines the key as string: the YAML loading always produces string map
/// keys, a map built in code with another key type is rejected instead of leaving `key` unset.
public class KeyInjector {

  private KeyInjector() {}

  /// Walks through all Map<K,V> components of any record R, and for each V that is a [HasKey]
  /// record with toBuilder()/build() and a key(String) setter on its builder, injects the map-key
  /// into its 'key' component. A `null` value, e.g. `api:` in YAML, is kept as it is.
  ///
  /// @param obj any record with a toBuilder()/build() builder
  /// @param <R> record type
  /// @return a brand-new record of type R with all nested maps updated
  /// @throws IllegalArgumentException if the map key of a [HasKey] value is not a string
  /// @throws IllegalStateException if a [HasKey] record has no key(String) builder setter
  @SuppressWarnings("unchecked")
  public static <R> R injectAllMapKeys(R obj) {
    try {
      Class<?> clazz = obj.getClass();
      Method rootToBuilder = clazz.getMethod("toBuilder");
      Object builder = rootToBuilder.invoke(obj);
      for (RecordComponent component : clazz.getRecordComponents()) {
        if (!Map.class.isAssignableFrom(component.getType())) {
          continue;
        }
        String name = component.getName();
        // getter is just the component name
        Method getter = clazz.getMethod(name);
        Map<Object, Object> rawMap = (Map<Object, Object>) getter.invoke(obj);
        if (rawMap == null) {
          continue;
        }
        Map<Object, Object> newMap = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : rawMap.entrySet()) {
          Object key = e.getKey();
          Object val = e.getValue();
          // an entry without settings, e.g. `api:` in YAML, is reported by the component
          if (val != null
              && val.getClass().isRecord()
              && HasKey.class.isAssignableFrom(val.getClass())) {
            if (!(key instanceof String)) {
              throw new IllegalArgumentException(
                  "Map key of "
                      + clazz.getSimpleName()
                      + "."
                      + name
                      + " must be a string, was: "
                      + (key == null ? "null" : key + " (" + key.getClass().getName() + ")"));
            }
            Method valueToBuilder = val.getClass().getMethod("toBuilder");
            Object valueBuilder = valueToBuilder.invoke(val);
            Method keySetter = valueBuilder.getClass().getMethod("key", String.class);
            keySetter.invoke(valueBuilder, key);
            Method build = valueBuilder.getClass().getMethod("build");
            val = build.invoke(valueBuilder);
          }
          newMap.put(key, val);
        }
        Method mapSetter = builder.getClass().getMethod(name, Map.class);
        mapSetter.invoke(builder, newMap);
      }
      Method buildRoot = builder.getClass().getMethod("build");
      return (R) buildRoot.invoke(builder);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Failed to inject keys via reflection", ex);
    }
  }
}
