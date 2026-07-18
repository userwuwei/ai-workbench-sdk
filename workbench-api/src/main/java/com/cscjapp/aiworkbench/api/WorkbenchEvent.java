package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class WorkbenchEvent {
  private final String type, message;
  private final Map<String, Object> data;

  public WorkbenchEvent(String type, String message, Map<String, ?> data) {
    this.type = type;
    this.message = message;
    this.data = new LinkedHashMap<>();
    if (data != null) this.data.putAll(data);
  }

  public String type() {
    return type;
  }

  public String message() {
    return message;
  }

  public Map<String, Object> data() {
    return Collections.unmodifiableMap(data);
  }
}
