package com.cscjapp.aiworkbench.core;

public interface ModelStreamObserver {
  void onDelta(String content, String reasoning);

  /**
   * Structured stream callback. The default preserves source compatibility for existing gateways
   * and observers that only understand content/reasoning deltas.
   */
  default void onStreamDelta(ModelStreamDelta delta) {
    if (delta != null) onDelta(delta.content(), delta.reasoning());
  }

  void onComplete(ModelResponse response);

  void onError(Throwable error);
}
