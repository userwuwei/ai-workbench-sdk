package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class ToolSpec {
  private final String name, description;
  private final Map<String, Object> inputSchema;

  public ToolSpec(String name, String description, Map<String, ?> inputSchema) {
    if (name == null || !name.matches("[a-zA-Z0-9_-]+"))
      throw new IllegalArgumentException("invalid tool name");
    this.name = name;
    this.description = description == null ? "" : description;
    this.inputSchema = new LinkedHashMap<>();
    if (inputSchema != null) this.inputSchema.putAll(inputSchema);
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public Map<String, Object> inputSchema() {
    return Collections.unmodifiableMap(inputSchema);
  }
}
