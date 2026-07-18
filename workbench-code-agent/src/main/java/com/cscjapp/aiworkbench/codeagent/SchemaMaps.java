package com.cscjapp.aiworkbench.codeagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SchemaMaps {
  private SchemaMaps() {}

  static Map<String, Object> copyMap(Map<String, ?> source) {
    Map<String, Object> output = new LinkedHashMap<>();
    if (source != null) {
      for (Map.Entry<String, ?> entry : source.entrySet()) {
        output.put(entry.getKey(), copy(entry.getValue()));
      }
    }
    return output;
  }

  static Object copy(Object value) {
    if (value instanceof Map) return copyMap((Map<String, ?>) value);
    if (value instanceof List) {
      List<Object> output = new ArrayList<>();
      for (Object item : (List<?>) value) output.add(copy(item));
      return output;
    }
    return value;
  }

  static Map<String, Object> merge(Map<String, ?> base, Map<String, ?> extension) {
    Map<String, Object> output = copyMap(base);
    if (extension == null) return output;
    for (Map.Entry<String, ?> entry : extension.entrySet()) {
      Object current = output.get(entry.getKey());
      Object incoming = entry.getValue();
      if (current instanceof Map && incoming instanceof Map) {
        output.put(
            entry.getKey(),
            merge((Map<String, ?>) current, (Map<String, ?>) incoming));
      } else if ("required".equals(entry.getKey())
          && current instanceof List
          && incoming instanceof List) {
        List<Object> merged = new ArrayList<>((List<?>) current);
        for (Object item : (List<?>) incoming) if (!merged.contains(item)) merged.add(copy(item));
        output.put(entry.getKey(), merged);
      } else {
        output.put(entry.getKey(), copy(incoming));
      }
    }
    return output;
  }
}
