package com.cscjapp.aiworkbench.api;

/** Stable artifact identity plus the short label shown in the shared context chip UI. */
public final class WorkbenchContextItem {
  private final String id;
  private final String label;

  public WorkbenchContextItem(String id, String label) {
    this.id = id == null ? "" : id;
    this.label = label == null || label.trim().isEmpty() ? this.id : label.trim();
  }

  public String id() { return id; }

  public String label() { return label; }
}
