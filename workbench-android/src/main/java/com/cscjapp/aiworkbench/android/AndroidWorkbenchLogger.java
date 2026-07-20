package com.cscjapp.aiworkbench.android;

import android.util.Log;
import com.cscjapp.aiworkbench.core.WorkbenchLogger;

/** Writes readable model diagnostics to Logcat without truncating long payloads. */
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
    if (value.isEmpty()) {
      print(name, "");
      return;
    }
    int start = 0;
    while (start < value.length()) {
      int end = Math.min(value.length(), start + CHUNK_SIZE);
      if (end < value.length()) {
        int lineBreak = value.lastIndexOf('\n', end);
        if (lineBreak > start) end = lineBreak + 1;
      }
      print(name, value.substring(start, end));
      start = end;
    }
  }

  private void print(String event, String value) {
    if ("model_error".equals(event)) {
      Log.e(tag, value);
    } else if ("model_request".equals(event)) {
      Log.i(tag, value);
    } else {
      Log.d(tag, value);
    }
  }
}
