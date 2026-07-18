package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class WorkbenchLaunchRequest {
  private final String definitionId, workspaceId, initialDemand, selectedArtifact;
  private final boolean deepThinking;
  private final Map<String, String> extras;

  private WorkbenchLaunchRequest(Builder b) {
    definitionId = b.definitionId;
    workspaceId = b.workspaceId;
    initialDemand = b.initialDemand;
    selectedArtifact = b.selectedArtifact;
    deepThinking = b.deepThinking;
    extras = Collections.unmodifiableMap(new LinkedHashMap<>(b.extras));
  }

  public static Builder builder(String definitionId) {
    return new Builder(definitionId);
  }

  public String definitionId() {
    return definitionId;
  }

  public String workspaceId() {
    return workspaceId;
  }

  public String initialDemand() {
    return initialDemand;
  }

  public String selectedArtifact() {
    return selectedArtifact;
  }

  public boolean deepThinking() {
    return deepThinking;
  }

  public Map<String, String> extras() {
    return extras;
  }

  /** Returns an equivalent request with the current UI deep-thinking selection. */
  public WorkbenchLaunchRequest withDeepThinking(boolean value) {
    Builder builder = builder(definitionId)
        .workspaceId(workspaceId)
        .initialDemand(initialDemand)
        .selectedArtifact(selectedArtifact)
        .deepThinking(value);
    for (Map.Entry<String, String> entry : extras.entrySet()) {
      builder.extra(entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  public static final class Builder {
    private final String definitionId;
    private String workspaceId = "", initialDemand = "", selectedArtifact = "";
    private boolean deepThinking;
    private final Map<String, String> extras = new LinkedHashMap<>();

    private Builder(String id) {
      if (id == null || id.trim().isEmpty())
        throw new IllegalArgumentException("definitionId required");
      definitionId = id.trim();
    }

    public Builder workspaceId(String v) {
      workspaceId = safe(v);
      return this;
    }

    public Builder initialDemand(String v) {
      initialDemand = safe(v);
      return this;
    }

    public Builder selectedArtifact(String v) {
      selectedArtifact = safe(v);
      return this;
    }

    public Builder deepThinking(boolean v) {
      deepThinking = v;
      return this;
    }

    public Builder extra(String k, String v) {
      if (k == null || k.trim().isEmpty()) throw new IllegalArgumentException("extra key required");
      extras.put(k.trim(), safe(v));
      return this;
    }

    public WorkbenchLaunchRequest build() {
      return new WorkbenchLaunchRequest(this);
    }

    private static String safe(String v) {
      return v == null ? "" : v;
    }
  }
}
