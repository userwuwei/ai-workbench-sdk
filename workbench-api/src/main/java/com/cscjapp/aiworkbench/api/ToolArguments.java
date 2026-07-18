package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class ToolArguments {
  private final Map<String, Object> values;

  public ToolArguments() {
    this.values = new LinkedHashMap<>();
  }

  public static ToolArguments empty() {
    return new ToolArguments();
  }

  public ToolArguments(Map<String, ?> source) {
    this.values = copy(source);
  }

  private static Map<String, Object> copy(Map<String, ?> source) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (source != null)
      for (Map.Entry<String, ?> e : source.entrySet()) out.put(e.getKey(), deepCopy(e.getValue()));
    return out;
  }

  private static Object deepCopy(Object value) {
    if (value instanceof Map) return copy((Map<String, ?>) value);
    if (value instanceof List) {
      List<Object> out = new ArrayList<>();
      for (Object v : (List<?>) value) out.add(deepCopy(v));
      return out;
    }
    return value;
  }

  public String getString(String key, String fallback) {
    Object v = values.get(key);
    return v == null ? fallback : String.valueOf(v);
  }

  public boolean getBoolean(String key, boolean fallback) {
    Object v = values.get(key);
    return v instanceof Boolean
        ? (Boolean) v
        : v == null ? fallback : Boolean.parseBoolean(String.valueOf(v));
  }

  public int getInt(String key, int fallback) {
    Object v = values.get(key);
    if (v instanceof Number) return ((Number) v).intValue();
    try {
      return v == null ? fallback : Integer.parseInt(String.valueOf(v));
    } catch (Exception e) {
      return fallback;
    }
  }

  public Object get(String key) {
    return values.get(key);
  }

  public boolean has(String key) {
    return values.containsKey(key);
  }

  public ToolArguments with(String key, Object value) {
    Map<String, Object> out = copy(values);
    out.put(key, deepCopy(value));
    return new ToolArguments(out);
  }

  public Map<String, Object> asMap() {
    return Collections.unmodifiableMap(copy(values));
  }
}
