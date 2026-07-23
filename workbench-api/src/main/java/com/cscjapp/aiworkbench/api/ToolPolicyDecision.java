package com.cscjapp.aiworkbench.api;

import java.util.Map;

public final class ToolPolicyDecision {
  public enum Kind {
    PROCEED,
    CANCEL,
    ERROR
  }

  private final Kind kind;
  private final ToolArguments arguments;
  private final ToolResult result;

  private ToolPolicyDecision(Kind kind, ToolArguments arguments, ToolResult result) {
    this.kind = kind;
    this.arguments = arguments;
    this.result = result;
  }

  public static ToolPolicyDecision proceed(ToolArguments args) {
    return new ToolPolicyDecision(Kind.PROCEED, args, null);
  }

  public static ToolPolicyDecision cancel(String message) {
    return new ToolPolicyDecision(Kind.CANCEL, null, ToolResult.cancelled(message));
  }

  public static ToolPolicyDecision error(String code, String message) {
    return error(code, message, false);
  }

  public static ToolPolicyDecision error(String code, String message, boolean retryable) {
    return new ToolPolicyDecision(
        Kind.ERROR, null, ToolResult.error(code, message, retryable));
  }

  public static ToolPolicyDecision error(
      String code, String message, boolean retryable, Map<String, ?> data) {
    return new ToolPolicyDecision(
        Kind.ERROR, null, ToolResult.error(code, message, retryable, data));
  }

  public Kind kind() {
    return kind;
  }

  public ToolArguments arguments() {
    return arguments;
  }

  public ToolResult result() {
    return result;
  }
}
