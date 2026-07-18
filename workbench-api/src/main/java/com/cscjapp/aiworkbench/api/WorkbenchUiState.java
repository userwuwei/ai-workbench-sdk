package com.cscjapp.aiworkbench.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Host-owned labels and capability state rendered in the shared reference workbench UI. */
public final class WorkbenchUiState {
  private final String projectName;
  private final String userName;
  private final String userAvatarUrl;
  private final String modelLabel;
  private final boolean deepThinkingSupported;
  private final boolean contextSelectionVisible;
  private final boolean reportVisible;
  private final List<String> contextLabels;
  private final List<WorkbenchContextItem> contextItems;

  private WorkbenchUiState(Builder builder) {
    projectName = safe(builder.projectName);
    userName = safe(builder.userName);
    userAvatarUrl = safe(builder.userAvatarUrl);
    modelLabel = safe(builder.modelLabel);
    deepThinkingSupported = builder.deepThinkingSupported;
    contextSelectionVisible = builder.contextSelectionVisible;
    reportVisible = builder.reportVisible;
    contextItems = Collections.unmodifiableList(new ArrayList<>(builder.contextItems));
    ArrayList<String> labels = new ArrayList<>();
    for (WorkbenchContextItem item : contextItems) labels.add(item.label());
    contextLabels = Collections.unmodifiableList(labels);
  }

  public String projectName() { return projectName; }
  public String userName() { return userName; }
  public String userAvatarUrl() { return userAvatarUrl; }
  public String modelLabel() { return modelLabel; }
  public boolean deepThinkingSupported() { return deepThinkingSupported; }
  public boolean contextSelectionVisible() { return contextSelectionVisible; }
  public boolean reportVisible() { return reportVisible; }
  public List<String> contextLabels() { return contextLabels; }
  public List<WorkbenchContextItem> contextItems() { return contextItems; }

  public static Builder builder() { return new Builder(); }

  public static final class Builder {
    private String projectName = "AI 工作台";
    private String userName = "你";
    private String userAvatarUrl = "";
    private String modelLabel = "默认模型";
    private boolean deepThinkingSupported;
    private boolean contextSelectionVisible;
    private boolean reportVisible;
    private final List<WorkbenchContextItem> contextItems = new ArrayList<>();

    public Builder projectName(String value) { projectName = value; return this; }
    public Builder userName(String value) { userName = value; return this; }
    public Builder userAvatarUrl(String value) { userAvatarUrl = value; return this; }
    public Builder modelLabel(String value) { modelLabel = value; return this; }
    public Builder deepThinkingSupported(boolean value) { deepThinkingSupported = value; return this; }
    public Builder contextSelectionVisible(boolean value) { contextSelectionVisible = value; return this; }
    public Builder reportVisible(boolean value) { reportVisible = value; return this; }
    public Builder contextLabels(List<String> values) {
      contextItems.clear();
      if (values != null) for (String value : values) {
        if (value != null && !value.trim().isEmpty()) {
          String clean = value.trim();
          contextItems.add(new WorkbenchContextItem(clean, clean));
        }
      }
      return this;
    }
    public Builder contextItems(List<WorkbenchContextItem> values) {
      contextItems.clear();
      if (values != null) for (WorkbenchContextItem value : values) {
        if (value != null && !value.id().trim().isEmpty()) contextItems.add(value);
      }
      return this;
    }
    public WorkbenchUiState build() { return new WorkbenchUiState(this); }
  }

  private static String safe(String value) { return value == null ? "" : value; }
}
