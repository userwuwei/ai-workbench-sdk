package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import com.google.gson.Gson;
import java.util.*;

public final class ToolResultCodec {
  private static final Gson GSON = new Gson();

  private ToolResultCodec() {}

  public static String toJson(ToolResult r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", r.status().name().toLowerCase());
    if (!r.data().isEmpty()) m.put("data", r.data());
    if (!r.errorCode().isEmpty()) m.put("error_code", r.errorCode());
    if (!r.message().isEmpty()) m.put("message", r.message());
    m.put("retryable", r.retryable());
    return GSON.toJson(m);
  }
}
