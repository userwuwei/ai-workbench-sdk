package com.cscjapp.aiworkbench.api;

public interface AccessPolicy {
  void check(String action, WorkbenchLaunchRequest request, Callback callback);

  interface Callback {
    void allow();

    void deny(String message, String hostActionId);
  }
}
