package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class ValidationResult {
  private final List<ValidationIssue> issues;

  public ValidationResult(List<ValidationIssue> issues) {
    this.issues =
        issues == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(issues));
  }

  public static ValidationResult success() {
    return new ValidationResult(Collections.emptyList());
  }

  public List<ValidationIssue> issues() {
    return issues;
  }

  public boolean passed() {
    for (ValidationIssue i : issues)
      if (i.severity() == ValidationIssue.Severity.BLOCKER) return false;
    return true;
  }
}
