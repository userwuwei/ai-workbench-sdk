package com.cscjapp.aiworkbench.api;

public final class WorkbenchAction {
  public enum Placement {
    TOOLBAR,
    QUICK
  }

  public enum Kind {
    HOST,
    PROMPT
  }

  private final String id, label, value;
  private final Placement placement;
  private final Kind kind;

  public WorkbenchAction(String id, String label, Placement placement, Kind kind, String value) {
    if (id == null
        || id.trim().isEmpty()
        || label == null
        || label.trim().isEmpty()
        || placement == null
        || kind == null)
      throw new IllegalArgumentException("action id/label/placement/kind required");
    this.id = id.trim();
    this.label = label.trim();
    this.placement = placement;
    this.kind = kind;
    this.value = value == null ? "" : value;
  }

  public String id() {
    return id;
  }

  public String label() {
    return label;
  }

  public Placement placement() {
    return placement;
  }

  public Kind kind() {
    return kind;
  }

  public String value() {
    return value;
  }

  public static WorkbenchAction host(String id, String label, Placement p) {
    return new WorkbenchAction(id, label, p, Kind.HOST, "");
  }

  public static WorkbenchAction prompt(String id, String label, String prompt) {
    return new WorkbenchAction(id, label, Placement.QUICK, Kind.PROMPT, prompt);
  }
}
