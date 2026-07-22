package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.ToolArguments;
import java.util.*;

/** Normalizes legacy plan shapes without causing a model repair round. */
final class CodePlanNormalizer {
  static final int VERSION = 1;
  private static final int MAX_STEPS = 5;
  private static final Set<String> PHASES =
      new LinkedHashSet<>(Arrays.asList("discover", "implement", "verify", "quality"));
  private final CodeValidationContract contract;

  CodePlanNormalizer(CodeValidationContract contract) {
    this.contract = contract;
  }

  Map<String, Object> normalize(Map<String, ?> source) {
    Map<String, Object> out = new LinkedHashMap<>(new ToolArguments(source).asMap());
    out.put("goal", limit(text(out.get("goal")), 160));
    String qualityMode = text(out.get("quality_mode"));
    if (!"interface_product".equals(qualityMode)) qualityMode = "standard";
    out.put("quality_mode", qualityMode);
    String writingMode = text(out.get("writing_mode"));
    if (!Arrays.asList("targeted_edit", "single_file", "multi_file_modular", "staged_generation")
        .contains(writingMode)) {
      writingMode = "interface_product".equals(qualityMode) ? "staged_generation" : "targeted_edit";
    }
    out.put("writing_mode", writingMode);
    if (!out.containsKey("quality_bar") || text(out.get("quality_bar")).isEmpty()) {
      Map<String, Object> quality = new LinkedHashMap<>();
      quality.put("functionality", "完成用户目标并形成可运行闭环");
      quality.put("verification", "使用真实工具验证，不以文字声明代替");
      out.put("quality_bar", quality);
    }
    out.put("planned_files", normalizeFiles(out.get("planned_files")));
    List<String> verification = strings(out.get("verification_plan"), 6, 160);
    if (verification.isEmpty()) verification.addAll(contract.requiredEvidence("code_generation"));
    out.put("verification_plan", verification);
    List<Map<String, Object>> steps = normalizeSteps(out.get("steps"));
    addDefaults(steps, qualityMode, verificationTools(verification));
    out.put("steps", steps);
    String reason = limit(text(out.get("replan_reason")), 200);
    if (reason.isEmpty()) out.remove("replan_reason");
    else out.put("replan_reason", reason);
    return out;
  }

  private static List<Map<String, Object>> normalizeFiles(Object raw) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<?> values = raw instanceof List ? (List<?>) raw
        : text(raw).isEmpty() ? Collections.emptyList() : Collections.singletonList(raw);
    for (Object value : values) {
      if (out.size() >= 8) break;
      Map<String, Object> file = new LinkedHashMap<>();
      if (value instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) value;
        String path = limit(first(map, "path", "file", "name"), 300);
        if (path.isEmpty()) continue;
        file.put("path", path);
        String action = first(map, "action", "operation");
        if ("create".equals(action) || "edit".equals(action)) file.put("action", action);
        String purpose = limit(first(map, "purpose", "responsibility", "description"), 120);
        if (!purpose.isEmpty()) file.put("purpose", purpose);
      } else {
        String path = limit(text(value), 300);
        if (path.isEmpty()) continue;
        file.put("path", path);
      }
      out.add(file);
    }
    return out;
  }

  private static List<Map<String, Object>> normalizeSteps(Object raw) {
    if (!(raw instanceof List)) return new ArrayList<>();
    List<Map<String, Object>> out = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();
    int index = 0;
    for (Object value : (List<?>) raw) {
      index++;
      if (out.size() >= MAX_STEPS) break;
      Map<?, ?> map = value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
      String numericStep = text(map.get("step"));
      String title = first(map, "title", "action", "description", "name");
      if (title.isEmpty() && !numeric(numericStep)) title = numericStep;
      if (title.isEmpty() && !(value instanceof Map)) title = text(value);
      if (title.isEmpty() || numeric(title)) continue;
      List<String> tools = strings(firstValue(map, "required_tools", "tools", "tool"), 4, 64);
      String phase = text(map.get("phase")).toLowerCase(Locale.ROOT);
      if (!PHASES.contains(phase)) phase = inferPhase(tools, title);
      String id = first(map, "id");
      if (id.isEmpty() && numeric(numericStep)) id = "step-" + numericStep.replaceAll("[^0-9]", "");
      if (id.isEmpty()) id = phase + "-" + index;
      Map<String, Object> step = new LinkedHashMap<>();
      step.put("id", uniqueId(limit(id, 80), ids));
      step.put("title", limit(title, 100));
      step.put("phase", phase);
      step.put("required_tools", tools);
      step.put("acceptance", strings(firstValue(map, "acceptance", "acceptance_criteria", "criteria"), 2, 120));
      out.add(step);
    }
    return out;
  }

  private static void addDefaults(
      List<Map<String, Object>> steps, String qualityMode, List<String> verificationTools) {
    List<Map<String, Object>> defaults = Arrays.asList(
        step("discover", "读取并确认真实上下文", "discover", Collections.emptyList()),
        step("implement", "完成核心实现与接入", "implement", Collections.emptyList()),
        step("verify", "执行真实验证", "verify", verificationTools),
        step("quality", "提交结构化质量自查", "quality", Collections.singletonList("quality_review")));
    if (steps.isEmpty()) {
      steps.addAll(defaults);
      return;
    }
    Set<String> phases = phases(steps);
    for (Map<String, Object> candidate : defaults) {
      if (steps.size() >= 3) break;
      String phase = text(candidate.get("phase"));
      if (phases.add(phase)) steps.add(candidate);
    }
    if ("interface_product".equals(qualityMode) && !phases.contains("quality")) {
      if (steps.size() >= MAX_STEPS) steps.remove(steps.size() - 1);
      steps.add(defaults.get(3));
    }
    while (steps.size() < 3) steps.add(defaults.get(steps.size()));
  }

  private static Set<String> phases(List<Map<String, Object>> steps) {
    Set<String> result = new LinkedHashSet<>();
    for (Map<String, Object> step : steps) result.add(text(step.get("phase")));
    return result;
  }

  private static Map<String, Object> step(String id, String title, String phase, List<String> tools) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    out.put("title", title);
    out.put("phase", phase);
    out.put("required_tools", new ArrayList<>(tools));
    out.put("acceptance", Collections.singletonList(title));
    return out;
  }

  private static String inferPhase(List<String> tools, String title) {
    for (String tool : tools) {
      if (tool.startsWith("read") || "list_dir".equals(tool)) return "discover";
      if (isWriteTool(tool)) return "implement";
      if ("quality_review".equals(tool)) return "quality";
      if (isVerifyTool(tool)) return "verify";
    }
    String lower = title.toLowerCase(Locale.ROOT);
    if (lower.contains("quality") || title.contains("质量") || title.contains("审查")) return "quality";
    if (lower.contains("test") || lower.contains("check") || title.contains("验证") || title.contains("检查")) return "verify";
    if (title.contains("读取") || title.contains("分析") || title.contains("确认")) return "discover";
    return "implement";
  }

  static boolean isWriteTool(String tool) {
    return "create_file".equals(tool) || "search_replace".equals(tool) || "rewrite".equals(tool);
  }

  static boolean isVerifyTool(String tool) {
    return tool != null && (tool.endsWith("_test") || tool.endsWith("_check") || tool.startsWith("verify"));
  }

  private static List<String> verificationTools(List<String> verification) {
    List<String> out = new ArrayList<>();
    for (String item : verification) {
      String value = text(item);
      int colon = value.indexOf(':');
      if (colon < 0) colon = value.indexOf('：');
      if (colon >= 0) value = value.substring(0, colon).trim();
      int space = value.indexOf(' ');
      if (space > 0) value = value.substring(0, space).trim();
      if (!value.isEmpty() && !out.contains(value)) out.add(value);
    }
    return out;
  }

  private static List<String> strings(Object raw, int maxItems, int maxLength) {
    List<String> out = new ArrayList<>();
    List<?> values = raw instanceof List ? (List<?>) raw
        : text(raw).isEmpty() ? Collections.emptyList() : Collections.singletonList(raw);
    for (Object value : values) {
      String item = limit(text(value), maxLength);
      if (!item.isEmpty() && !out.contains(item)) out.add(item);
      if (out.size() >= maxItems) break;
    }
    return out;
  }

  private static String uniqueId(String base, Set<String> ids) {
    String value = base.isEmpty() ? "step" : base;
    String candidate = value;
    int suffix = 2;
    while (!ids.add(candidate)) candidate = value + "-" + suffix++;
    return candidate;
  }

  private static Object firstValue(Map<?, ?> map, String... keys) {
    for (String key : keys) if (map.containsKey(key)) return map.get(key);
    return null;
  }

  private static String first(Map<?, ?> map, String... keys) {
    return text(firstValue(map, keys));
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String limit(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static boolean numeric(String value) {
    return !value.isEmpty() && value.matches("[0-9]+[.)、]?");
  }
}
