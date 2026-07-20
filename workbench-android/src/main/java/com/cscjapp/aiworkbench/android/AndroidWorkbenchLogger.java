package com.cscjapp.aiworkbench.android;

import android.util.Log;
import com.cscjapp.aiworkbench.core.WorkbenchLogger;

/** Writes complete model diagnostics to Logcat without truncating long payloads. */
public final class AndroidWorkbenchLogger implements WorkbenchLogger {
  private static final int CHUNK_SIZE = 3000;
  private final String tag;

  public AndroidWorkbenchLogger(String tag) {
    this.tag = tag == null || tag.trim().isEmpty() ? "AIWorkbench" : tag.trim();
  }

  @Override
  public void log(String event, String message) {
    String name = event == null || event.trim().isEmpty() ? "diagnostic" : event.trim();
    String value = message == null ? "" : message;
    int total = Math.max(1, (value.length() + CHUNK_SIZE - 1) / CHUNK_SIZE);
    if (value.isEmpty()) {
      Log.d(tag, "[" + name + "]");
      return;
    }
    for (int part = 0; part < total; part++) {
      int start = part * CHUNK_SIZE;
      int end = Math.min(value.length(), start + CHUNK_SIZE);
      String prefix = total == 1
          ? "[" + name + "] "
          : "[" + name + "][" + (part + 1) + "/" + total + "] ";
      Log.d(tag, prefix + value.substring(start, end));
    }
  }
}
