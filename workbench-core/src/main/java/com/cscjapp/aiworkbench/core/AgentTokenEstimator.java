package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.ToolSpec;
import com.google.gson.Gson;
import java.util.List;

/** Conservative, deterministic request-size estimator used before an outbound request exists. */
final class AgentTokenEstimator {
  private static final Gson GSON = new Gson();

  private AgentTokenEstimator() {}

  static long messages(List<AgentMessage> messages) {
    double tokens = 8d;
    if (messages != null) {
      for (AgentMessage message : messages) {
        if (message == null) continue;
        tokens += 12d;
        tokens += jsonText(message.role().name());
        tokens += jsonText(message.content());
        tokens += jsonText(message.name());
        tokens += jsonText(message.toolCallId());
        for (AgentToolCall call : message.toolCalls()) {
          tokens += 16d + jsonText(call.id()) + jsonText(call.name());
          if (call.arguments() != null) tokens += text(GSON.toJson(call.arguments().asMap()));
        }
      }
    }
    return (long) Math.ceil(tokens);
  }

  static long tools(List<ToolSpec> tools) {
    double tokens = 8d;
    if (tools != null) {
      for (ToolSpec tool : tools) {
        if (tool == null) continue;
        tokens += 24d + jsonText(tool.name()) + jsonText(tool.description());
        tokens += text(GSON.toJson(tool.inputSchema()));
      }
    }
    return (long) Math.ceil(tokens);
  }

  static long total(List<AgentMessage> messages, List<ToolSpec> tools) {
    // Request envelope, model fields, JSON array delimiters and tool_choice defaults.
    return messages(messages) + tools(tools) + 64L;
  }

  static long text(String value) {
    if (value == null || value.isEmpty()) return 0L;
    double tokens = 0d;
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      if (c <= 0x007f) tokens += Character.isWhitespace(c) ? 0.15d : 0.28d;
      else if (isCjk(c)) tokens += 1d;
      else tokens += 0.65d;
    }
    return (long) Math.ceil(tokens);
  }

  private static long jsonText(String value) {
    return text(GSON.toJson(value == null ? "" : value));
  }

  private static boolean isCjk(char c) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
        || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        || block == Character.UnicodeBlock.GENERAL_PUNCTUATION;
  }
}
