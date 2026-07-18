package com.cscjapp.aiworkbench.api;

public interface ToolCallback {
  void onProgress(String stage, long current, long total, String message);

  void onComplete(ToolResult result);
}
