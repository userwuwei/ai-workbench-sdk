package com.cscjapp.aiworkbench.core;

/** Lightweight diagnostic sink for model interaction logs. */
@FunctionalInterface
public interface WorkbenchLogger {
  void log(String event, String message);

  static WorkbenchLogger none() {
    return NoOpHolder.INSTANCE;
  }

  final class NoOpHolder {
    private static final WorkbenchLogger INSTANCE = (event, message) -> {};
  }
}
