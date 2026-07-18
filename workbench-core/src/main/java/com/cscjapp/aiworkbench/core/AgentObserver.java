package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;

public interface AgentObserver {
  void onState(String state);

  void onDelta(String content, String reasoning);

  /** Structured model delta; defaults to the original two-string callback. */
  default void onStreamDelta(ModelStreamDelta delta) {
    if (delta != null) onDelta(delta.content(), delta.reasoning());
  }

  void onToolStarted(String callId, String name, ToolArguments args);

  void onToolProgress(String callId, String stage, long current, long total, String message);

  void onToolCompleted(String callId, String name, ToolResult result);

  void onValidation(ValidationResult result);

  void onFinal(String content);

  void onError(Throwable error);
}
