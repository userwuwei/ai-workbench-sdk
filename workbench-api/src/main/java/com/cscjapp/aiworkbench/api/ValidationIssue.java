package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class ValidationIssue {
  public enum Severity {
    WARNING,
    BLOCKER
  }

  private final String code, message;
  private final Severity severity;
  private final Map<String, Object> evidence;

  public ValidationIssue(String code, String message, Severity severity, Map<String, ?> evidence) {
    this.code = code;
    this.message = message;
    this.severity = severity;
    this.evidence = new LinkedHashMap<>();
    if (evidence != null) this.evidence.putAll(evidence);
  }

  public String code() {
    return code;
  }

  public String message() {
    return message;
  }

  public Severity severity() {
    return severity;
  }

  public Map<String, Object> evidence() {
    return Collections.unmodifiableMap(evidence);
  }
}
