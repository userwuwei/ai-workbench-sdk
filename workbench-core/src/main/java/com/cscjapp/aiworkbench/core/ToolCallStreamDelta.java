package com.cscjapp.aiworkbench.core;

/** One OpenAI-compatible native tool-call fragment from a model stream. */
public final class ToolCallStreamDelta {
  private final int index;
  private final String id;
  private final String name;
  private final String arguments;

  public ToolCallStreamDelta(int index, String id, String name, String arguments) {
    this.index = Math.max(0, index);
    this.id = id == null ? "" : id;
    this.name = name == null ? "" : name;
    this.arguments = arguments == null ? "" : arguments;
  }

  public int index() {
    return index;
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  /** Raw incremental function.arguments text, not the final assembled JSON. */
  public String arguments() {
    return arguments;
  }
}
