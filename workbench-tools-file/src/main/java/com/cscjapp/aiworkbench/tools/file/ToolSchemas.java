package com.cscjapp.aiworkbench.tools.file;

import java.util.*;

final class ToolSchemas {
  private ToolSchemas() {}

  static Map<String, Object> object(String[][] properties, String... required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    Map<String, Object> props = new LinkedHashMap<>();
    for (String[] p : properties) {
      Map<String, Object> spec = new LinkedHashMap<>();
      spec.put("type", p[1]);
      if (p.length > 2) spec.put("description", p[2]);
      props.put(p[0], spec);
    }
    schema.put("properties", props);
    schema.put("required", Arrays.asList(required));
    schema.put("additionalProperties", false);
    return schema;
  }
}
