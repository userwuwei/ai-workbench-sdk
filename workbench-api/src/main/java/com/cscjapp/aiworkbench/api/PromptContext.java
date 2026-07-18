package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class PromptContext {
  private final String workspaceId, demand;
  private final Map<String, Object> runtime;

  public PromptContext(String workspaceId, String demand, Map<String, ?> runtime) {
    this.workspaceId = workspaceId;
    this.demand = demand;
    this.runtime = new LinkedHashMap<>();
    if (runtime != null) this.runtime.putAll(runtime);
  }

  public String workspaceId() {
    return workspaceId;
  }

  public String demand() {
    return demand;
  }

  public Map<String, Object> runtime() {
    return Collections.unmodifiableMap(runtime);
  }
}
