package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class ToolResult {
  public enum Status {
    SUCCESS,
    ERROR,
    CANCELLED
  }

  private final Status status;
  private final Map<String, Object> data;
  private final String errorCode;
  private final String message;
  private final boolean retryable;

  private ToolResult(
      Status status, Map<String, ?> data, String errorCode, String message, boolean retryable) {
    this.status = status;
    this.data = new LinkedHashMap<>();
    if (data != null) this.data.putAll(data);
    this.errorCode = safe(errorCode);
    this.message = safe(message);
    this.retryable = retryable;
  }

  public static ToolResult success(Map<String, ?> data) {
    return new ToolResult(Status.SUCCESS, data, "", "", false);
  }

  public static ToolResult success() {
    return success(Collections.emptyMap());
  }

  public static ToolResult error(String code, String message, boolean retryable) {
    return new ToolResult(Status.ERROR, null, code, message, retryable);
  }

  public static ToolResult cancelled(String message) {
    return new ToolResult(Status.CANCELLED, null, "user_cancelled", message, false);
  }

  public Status status() {
    return status;
  }

  public Map<String, Object> data() {
    return Collections.unmodifiableMap(data);
  }

  public String errorCode() {
    return errorCode;
  }

  public String message() {
    return message;
  }

  public boolean retryable() {
    return retryable;
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  private static String safe(String v) {
    return v == null ? "" : v;
  }
}
