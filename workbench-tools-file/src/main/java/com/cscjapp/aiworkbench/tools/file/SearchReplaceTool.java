package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class SearchReplaceTool extends AbstractFileTool {
  public SearchReplaceTool(WorkspaceAccess w) {
    super(w);
  }

  public ToolSpec spec() {
    Map<String, Object> replacementProperties = new LinkedHashMap<>();
    replacementProperties.put("old", property("string", "逐字复制自最新读取证据的旧文本"));
    replacementProperties.put("new", property("string", "替换后的新文本"));
    Map<String, Object> replacement = new LinkedHashMap<>();
    replacement.put("type", "object");
    replacement.put("properties", replacementProperties);
    replacement.put("required", Arrays.asList("old", "new"));
    replacement.put("additionalProperties", false);
    Map<String, Object> replacements = new LinkedHashMap<>();
    replacements.put("type", "array");
    replacements.put("description", "同一文件多个非重叠修改点应在一次调用中原子提交");
    replacements.put("items", replacement);
    replacements.put("minItems", 1);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("path", property("string", "已存在的目标路径"));
    properties.put("replacements", replacements);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", Arrays.asList("path", "replacements"));
    schema.put("additionalProperties", false);
    return new ToolSpec(
        "search_replace",
        "修改任何已有文件的唯一编辑工具，包括修复、重构、布局和样式升级；多个修改点使用 replacements[]",
        schema,
        true);
  }

  ToolResult run(ToolArguments a) throws Exception {
    String p = a.getString("path", "");
    String c = workspace.read(p);
    List<Replacement> requested = replacements(a);
    List<ResolvedReplacement> resolved = new ArrayList<>();
    for (int index = 0; index < requested.size(); index++) {
      Replacement item = requested.get(index);
      if (item.old.isEmpty()) throw new IllegalArgumentException("search_text_empty:" + index);
      List<Integer> matches = matches(c, item.old);
      if (matches.size() != 1) {
        throw new IllegalArgumentException(
            "search_match_count:" + index + ":expected=1:actual=" + matches.size());
      }
      for (Integer start : matches) {
        resolved.add(new ResolvedReplacement(start, item.old, item.newValue));
      }
    }
    resolved.sort(Comparator.comparingInt(item -> item.start));
    for (int index = 1; index < resolved.size(); index++) {
      ResolvedReplacement previous = resolved.get(index - 1);
      ResolvedReplacement current = resolved.get(index);
      if (current.start < previous.start + previous.old.length()) {
        throw new IllegalArgumentException("search_replacements_overlap");
      }
    }
    StringBuilder updated = new StringBuilder(c);
    for (int index = resolved.size() - 1; index >= 0; index--) {
      ResolvedReplacement item = resolved.get(index);
      updated.replace(item.start, item.start + item.old.length(), item.replacement);
    }
    workspace.writeAtomic(p, updated.toString(), true);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("path", p);
    d.put("replacements", requested.size());
    d.put("matched_occurrences", resolved.size());
    return ToolResult.success(d);
  }

  private static Map<String, Object> property(String type, String description) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("type", type);
    value.put("description", description);
    return value;
  }

  @SuppressWarnings("unchecked")
  private static List<Replacement> replacements(ToolArguments arguments) {
    List<Replacement> result = new ArrayList<>();
    Object raw = arguments.get("replacements");
    if (raw instanceof List) {
      for (Object value : (List<?>) raw) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("invalid_replacement");
        Map<String, Object> item = (Map<String, Object>) value;
        result.add(
            new Replacement(
                text(item.get("old")),
                text(item.get("new"))));
      }
    }
    if (result.isEmpty()) throw new IllegalArgumentException("replacements_required");
    return result;
  }

  private static List<Integer> matches(String content, String target) {
    List<Integer> result = new ArrayList<>();
    int cursor = 0;
    while (cursor <= content.length() - target.length()) {
      int found = content.indexOf(target, cursor);
      if (found < 0) break;
      result.add(found);
      cursor = found + Math.max(1, target.length());
    }
    return result;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static final class Replacement {
    final String old;
    final String newValue;

    Replacement(String old, String newValue) {
      this.old = old;
      this.newValue = newValue;
    }
  }

  private static final class ResolvedReplacement {
    final int start;
    final String old;
    final String replacement;

    ResolvedReplacement(int start, String old, String replacement) {
      this.start = start;
      this.old = old;
      this.replacement = replacement;
    }
  }
}
