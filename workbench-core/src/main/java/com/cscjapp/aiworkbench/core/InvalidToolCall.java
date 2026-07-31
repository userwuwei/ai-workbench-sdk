package com.cscjapp.aiworkbench.core;

/** A native tool call whose argument string was not a valid JSON object. */
public final class InvalidToolCall {
  private final String id;
  private final String name;
  private final String errorMessage;
  private final String invalidEscape;
  private final int errorOffset;
  private final int argumentChars;

  public InvalidToolCall(
      String id,
      String name,
      String errorMessage,
      String invalidEscape,
      int errorOffset,
      int argumentChars) {
    this.id = safe(id);
    this.name = safe(name);
    this.errorMessage = safe(errorMessage);
    this.invalidEscape = safe(invalidEscape);
    this.errorOffset = errorOffset;
    this.argumentChars = Math.max(0, argumentChars);
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String errorMessage() {
    return errorMessage;
  }

  public String invalidEscape() {
    return invalidEscape;
  }

  public int errorOffset() {
    return errorOffset;
  }

  public int argumentChars() {
    return argumentChars;
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
