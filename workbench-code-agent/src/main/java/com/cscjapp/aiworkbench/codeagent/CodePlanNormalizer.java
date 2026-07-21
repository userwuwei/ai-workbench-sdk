package com.cscjapp.aiworkbench.codeagent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tolerates provider-specific plan shapes while returning one stable UI/tool contract. */
final class CodePlanNormalizer {
  static final int VERSION = 1;
  private static final int MAX_JSON_LENGTH = 32 * 1024;
  private static final int MAX_JSON_DEPTH = 12;
  private static final int MAX_STEPS = 8;
  private static final int MAX_TITLE_LENGTH = 160;
  private static final Set<String> PHASES =
      new LinkedHashSet<>(Arrays.asList("discover", "implement", "verify", "quality", "finalize"));
  private final Map<String, Object> inputSchema;
  private final Gson gson = new Gson();

  CodePlanNormalizer(Map<String, Object> inputSchema) {
    this.inputSchema = inputSchema == null ? Collections.emptyMap() : inputSchema;
  }

  Map<String, Object> normalize(Map<String, ?> raw) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (raw != null) result.putAll(raw);
    List<String> warnings = new ArrayList<>();
    normalizeSchemaObjects(result, warnings);
    normalizeListField(result, "planned_files", warnings);
    normalizeListField(result, "verification_plan", warnings);
    normalizeListField(result, "deliverable_evidence", warnings);
    normalizeListField(result, "risks", warnings);

    List<Map<String, Object>> steps = normalizeSteps(result.get("steps"), warnings);
    if (steps.isEmpty()) steps = fallbackSteps(result);
    result.put("steps", steps);
    result.put("normalized_plan_version", VERSION);
    if (!warnings.isEmpty()) result.put("normalization_warnings", warnings);
    else result.remove("normalization_warnings");
    return result;
  }

  private void normalizeSchemaObjects(Map<String, Object> result, List<String> warnings) {
    Object rawProperties = inputSchema.get("properties");
    if (!(rawProperties instanceof Map)) return;
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawProperties).entrySet()) {
      if (!(entry.getValue() instanceof Map)) continue;
      String key = String.valueOf(entry.getKey());
      Object type = ((Map<?, ?>) entry.getValue()).get("type");
      Object value = result.get(key);
      if (!"object".equals(type) || !(value instanceof String)) continue;
      Object parsed = parseJson((String) value, key, warnings);
      if (parsed instanceof Map) result.put(key, parsed);
    }
  }

  private void normalizeListField(
      Map<String, Object> result, String key, List<String> warnings) {
    Object value = result.get(key);
    if (value instanceof String) {
      String stringValue = ((String) value).trim();
      Object parsed =
          stringValue.startsWith("[") ? parseJson(stringValue, key, warnings) : null;
      if (parsed instanceof List) value = parsed;
      else if (!stringValue.isEmpty()) value = Collections.singletonList(stringValue);
    }
    List<String> normalized = strings(value);
    if (!normalized.isEmpty() || result.containsKey(key)) result.put(key, normalized);
  }

  private List<Map<String, Object>> normalizeSteps(Object raw, List<String> warnings) {
    if (raw instanceof String) {
      String value = ((String) raw).trim();
      Object parsed = value.startsWith("[") ? parseJson(value, "steps", warnings) : null;
      raw = parsed == null ? Collections.singletonList(value) : parsed;
    }
    if (!(raw instanceof List)) return new ArrayList<>();
    List<Map<String, Object>> result = new ArrayList<>();
    int sourceIndex = 0;
    for (Object item : (List<?>) raw) {
      sourceIndex++;
      if (result.size() >= MAX_STEPS) {
        warnings.add("steps_truncated");
        break;
      }
      Map<String, Object> step = normalizeStep(item, sourceIndex);
      if (step != null) result.add(step);
    }
    return result;
  }

  private Map<String, Object> normalizeStep(Object raw, int index) {
    Map<?, ?> source = raw instanceof Map ? (Map<?, ?>) raw : Collections.emptyMap();
    String plain = raw instanceof Map ? "" : text(raw);
    String numericStep = text(source.get("step"));
    String title = first(source, "title", "action", "description", "name");
    if (title.isEmpty() && !isNumeric(numericStep)) title = numericStep;
    if (title.isEmpty()) title = plain;
    if (title.isEmpty() || isNumeric(title)) return null;
    title = trim(title, MAX_TITLE_LENGTH);

    List<String> tools = strings(firstValue(source, "required_tools", "tools", "tool"));
    List<String> acceptance =
        strings(firstValue(source, "acceptance", "acceptance_criteria", "criteria"));
    String phase = text(source.get("phase")).toLowerCase(Locale.ROOT);
    if (!PHASES.contains(phase)) phase = inferPhase(tools, title);
    String id = first(source, "id");
    if (id.isEmpty() && !numericStep.isEmpty()) id = "step-" + numericStep;
    if (id.isEmpty()) id = phase + "-" + index;

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", trim(id, 80));
    result.put("title", title);
    result.put("phase", phase);
    result.put("required_tools", tools);
    result.put("acceptance", acceptance);
    result.put("status", normalizeStatus(first(source, "status", "state")));
    return result;
  }

  private List<Map<String, Object>> fallbackSteps(Map<String, Object> plan) {
    List<Map<String, Object>> result = new ArrayList<>();
    String goal = trim(text(plan.get("goal")), 72);
    String files = join(strings(plan.get("planned_files")), 2);
    List<String> verification = strings(plan.get("verification_plan"));
    result.add(
        step(
            "implement",
            goal.isEmpty() ? "完成任务所需实现" : "完成实现：" + goal,
            "implement",
            Collections.<String>emptyList(),
            files.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.singletonList("更新 " + files)));
    if (!verification.isEmpty()) {
      result.add(
          step(
              "verify",
              "执行验证：" + join(verification, 2),
              "verify",
              verificationToolNames(verification),
              Collections.singletonList("验证结果通过")));
    }
    if (bool(plan.get("self_review_required"))
        || "interface_product".equals(text(plan.get("quality_mode")))) {
      result.add(
          step(
              "quality",
              "完成质量审查",
              "quality",
              Collections.singletonList("quality_review"),
              Collections.singletonList("不存在阻塞问题")));
    }
    result.add(
        step(
            "finalize",
            "核对证据并结束任务",
            "finalize",
            Collections.singletonList("finalize_task"),
            Collections.singletonList("终态审核通过")));
    return result;
  }

  private static Map<String, Object> step(
      String id, String title, String phase, List<String> tools, List<String> acceptance) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("title", title);
    result.put("phase", phase);
    result.put("required_tools", new ArrayList<>(tools));
    result.put("acceptance", new ArrayList<>(acceptance));
    result.put("status", "pending");
    return result;
  }

  private Object parseJson(String raw, String key, List<String> warnings) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) return null;
    if (value.length() > MAX_JSON_LENGTH) {
      warnings.add(key + "_json_too_large");
      return null;
    }
    try {
      JsonElement element = JsonParser.parseString(value);
      if (depth(element, 0) > MAX_JSON_DEPTH) {
        warnings.add(key + "_json_too_deep");
        return null;
      }
      return gson.fromJson(element, Object.class);
    } catch (RuntimeException error) {
      warnings.add(key + "_json_invalid");
      return null;
    }
  }

  private static int depth(JsonElement value, int level) {
    if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return level;
    int max = level;
    if (value.isJsonArray()) {
      for (JsonElement item : value.getAsJsonArray()) {
        max = Math.max(max, depth(item, level + 1));
      }
    } else {
      for (Map.Entry<String, JsonElement> item : value.getAsJsonObject().entrySet()) {
        max = Math.max(max, depth(item.getValue(), level + 1));
      }
    }
    return max;
  }

  private static String inferPhase(List<String> tools, String title) {
    for (String tool : tools) {
      if ("plan_task".equals(tool) || isReadTool(tool)) return "discover";
      if (isWriteTool(tool)) return "implement";
      if (isVerifyTool(tool)) return "verify";
      if ("quality_review".equals(tool)) return "quality";
      if ("finalize_task".equals(tool)) return "finalize";
    }
    String lower = title.toLowerCase(Locale.ROOT);
    if (lower.contains("quality") || title.contains("质量") || title.contains("审查")) {
      return "quality";
    }
    if (lower.contains("final") || title.contains("结束") || title.contains("收口")) {
      return "finalize";
    }
    if (lower.contains("test")
        || lower.contains("check")
        || title.contains("验证")
        || title.contains("检查")) return "verify";
    if (title.contains("读取") || title.contains("分析") || title.contains("确认")) return "discover";
    return "implement";
  }

  private static boolean isReadTool(String tool) {
    return tool.startsWith("read") || "list_dir".equals(tool) || "search_files".equals(tool);
  }

  private static boolean isWriteTool(String tool) {
    return "create_file".equals(tool)
        || "search_replace".equals(tool)
        || "rewrite".equals(tool);
  }

  private static boolean isVerifyTool(String tool) {
    return tool.endsWith("_test") || tool.endsWith("_check") || tool.startsWith("verify");
  }

  private static String normalizeStatus(String value) {
    if ("done".equals(value) || "completed".equals(value) || "success".equals(value)) return "done";
    if ("running".equals(value) || "active".equals(value) || "in_progress".equals(value)) {
      return "running";
    }
    if ("skipped".equals(value)) return "skipped";
    return "pending";
  }

  private static List<String> strings(Object raw) {
    List<String> result = new ArrayList<>();
    if (raw instanceof List) {
      for (Object value : (List<?>) raw) {
        String text = text(value);
        if (!text.isEmpty()) result.add(text);
      }
    } else {
      String text = text(raw);
      if (!text.isEmpty()) result.add(text);
    }
    return result;
  }

  private static Object firstValue(Map<?, ?> source, String... keys) {
    for (String key : keys) if (source.containsKey(key)) return source.get(key);
    return null;
  }

  private static String first(Map<?, ?> source, String... keys) {
    return text(firstValue(source, keys));
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String trim(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static boolean isNumeric(String value) {
    return !value.isEmpty() && value.matches("[0-9]+[.)、]?");
  }

  private static boolean bool(Object value) {
    return value instanceof Boolean ? (Boolean) value : "true".equalsIgnoreCase(text(value));
  }

  private static String join(List<String> values, int limit) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < values.size() && i < limit; i++) {
      if (result.length() > 0) result.append("、");
      result.append(values.get(i));
    }
    return result.toString();
  }

  private static List<String> verificationToolNames(List<String> values) {
    List<String> result = new ArrayList<>();
    for (String value : values) {
      String[] tokens = value.split("[^A-Za-z0-9_]+");
      for (String token : tokens) {
        if (token.matches("[A-Za-z][A-Za-z0-9_]*")
            && (token.endsWith("_test")
                || token.endsWith("_check")
                || token.startsWith("verify"))) {
          if (!result.contains(token)) result.add(token);
        }
      }
    }
    return result;
  }
}
