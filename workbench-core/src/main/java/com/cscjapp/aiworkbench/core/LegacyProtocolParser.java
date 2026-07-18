package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import com.google.gson.*;
import java.util.*;

public final class LegacyProtocolParser {
  public static final class Parsed {
    public final String callId, name;
    public final ToolArguments arguments;
    public final boolean finalize;

    Parsed(String i, String n, ToolArguments a, boolean f) {
      callId = i;
      name = n;
      arguments = a;
      finalize = f;
    }
  }

  public Parsed parse(String text) {
    try {
      String json = extract(text);
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject a =
          root.has("next_action") && root.get("next_action").isJsonObject()
              ? root.getAsJsonObject("next_action")
              : root;
      String name = str(a, "tool");
      if (name.isEmpty()) return null;
      boolean finish =
          Arrays.asList("none", "final", "finalize", "done", "finish").contains(name.toLowerCase());
      Map<String, Object> args = new LinkedHashMap<>();
      if (a.has("args") && a.get("args").isJsonObject())
        args = new Gson().fromJson(a.get("args"), Map.class);
      return new Parsed("legacy-" + System.nanoTime(), name, new ToolArguments(args), finish);
    } catch (Exception e) {
      return null;
    }
  }

  private static String extract(String s) {
    int a = s.indexOf('{'), b = s.lastIndexOf('}');
    if (a < 0 || b < a) throw new IllegalArgumentException();
    return s.substring(a, b + 1);
  }

  private static String str(JsonObject o, String k) {
    return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
  }
}
