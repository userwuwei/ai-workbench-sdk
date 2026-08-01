package com.cscjapp.aiworkbench.api;

/** Supplies an optional request-scoped required native tool name. */
public interface NamedToolChoicePolicy {
  /** Returns an empty string to keep the model request on automatic tool choice. */
  String requiredToolName(AgentRoundContext context);
}
