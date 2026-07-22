package com.cscjapp.aiworkbench.codeagent;

import java.util.Locale;

/** Controls whether an opt-in Code Agent run requires a managed plan. */
public enum CodePlanningMode {
  ADAPTIVE,
  FORCE,
  SKIP;

  public static CodePlanningMode from(String value) {
    if (value == null) return ADAPTIVE;
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return ADAPTIVE;
    }
  }
}
