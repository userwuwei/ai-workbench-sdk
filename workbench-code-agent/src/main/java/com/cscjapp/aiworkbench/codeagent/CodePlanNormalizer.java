package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.ToolArguments;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    List<Map<String, Object>> files = normalizeFiles(out.get("planned_files"));
    out.put("planned_files", files);
    List<String> verification = strings(out.get("verification_plan"), 6, 160);
    if (verification.isEmpty()) verification.addAll(contract.requiredEvidence("code_generation"));
    out.put("verification_plan", verification);
    List<Map<String, Object>> steps = normalizeSteps(out.get("steps"));
    addDefaults(steps, qualityMode, verificationTools(verification));
    mapImplementationFiles(steps, files);
    while (steps.size() > MAX_STEPS) mergeDuplicatePhase(steps);
    out.put("steps", steps);
    if (out.containsKey("interaction_checks") || bool(out.get("interaction_required"))) {
      NormalizedInteractionChecks normalized =
          normalizeInteractionChecks(out.get("interaction_checks"));
      if (requiredCount(normalized.checks) == 0 && bool(out.get("interaction_required"))) {
        if (normalized.checks.size() >= 5) {
          normalized.checks.remove(normalized.checks.size() - 1);
          normalized.warnings.add("为 core_interaction 保留硬门禁位置，末项 advisory 已省略");
        }
        normalized.checks.add(coreInteractionCheck());
        normalized.warnings.add("未收到完整的确定性交互契约，已生成 core_interaction 核心检查");
      }
      out.put("interaction_checks", normalized.checks);
      if (!normalized.warnings.isEmpty()) {
        out.put("interaction_check_warnings", normalized.warnings);
      } else {
        out.remove("interaction_check_warnings");
      }
    }
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
      file.put("file_id", fileId(text(file.get("path"))));
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
      step.put("file_refs", strings(firstValue(map, "file_refs", "files", "file_ids"), 8, 300));
      out.add(step);
    }
    return out;
  }

  private static void mapImplementationFiles(
      List<Map<String, Object>> steps, List<Map<String, Object>> files) {
    if (files.isEmpty()) return;
    Map<String, String> refs = new LinkedHashMap<>();
    for (Map<String, Object> file : files) {
      String id = text(file.get("file_id"));
      String path = normalizedPath(text(file.get("path")));
      refs.put(id, id);
      refs.put(path, id);
    }
    List<Map<String, Object>> implementation = new ArrayList<>();
    for (Map<String, Object> step : steps) {
      if (!"implement".equals(text(step.get("phase")))) continue;
      implementation.add(step);
      List<String> mapped = new ArrayList<>();
      for (String raw : strings(step.get("file_refs"), 8, 300)) {
        String id = refs.get(raw);
        if (id == null) id = refs.get(normalizedPath(raw));
        if (id != null && !mapped.contains(id)) mapped.add(id);
      }
      step.put("file_refs", mapped);
    }
    if (implementation.size() == 1) {
      Map<String, Object> step = implementation.get(0);
      if (((List<?>) step.get("file_refs")).isEmpty()) step.put("file_refs", fileIds(files));
      ensureAllFilesMapped(implementation, files);
      return;
    }
    if (implementation.size() == files.size()) {
      for (int index = 0; index < implementation.size(); index++) {
        Map<String, Object> step = implementation.get(index);
        if (((List<?>) step.get("file_refs")).isEmpty()) {
          step.put("file_refs", Collections.singletonList(files.get(index).get("file_id")));
        }
      }
      ensureAllFilesMapped(implementation, files);
      return;
    }
    boolean allExplicit = !implementation.isEmpty();
    for (Map<String, Object> step : implementation) {
      if (((List<?>) step.get("file_refs")).isEmpty()) allExplicit = false;
    }
    if (allExplicit) {
      ensureAllFilesMapped(implementation, files);
      return;
    }

    steps.removeIf(step -> "implement".equals(text(step.get("phase"))));
    int insertAt = 0;
    while (insertAt < steps.size() && "discover".equals(text(steps.get(insertAt).get("phase")))) {
      insertAt++;
    }
    Map<String, List<String>> groups = new LinkedHashMap<>();
    for (Map<String, Object> file : files) {
      String action = text(file.get("action"));
      if (!"create".equals(action) && !"edit".equals(action)) action = "write";
      groups.computeIfAbsent(action, ignored -> new ArrayList<>()).add(text(file.get("file_id")));
    }
    List<Map<String, Object>> generated = new ArrayList<>();
    for (Map.Entry<String, List<String>> group : groups.entrySet()) {
      String action = group.getKey();
      String title = "create".equals(action) ? "创建计划文件"
          : "edit".equals(action) ? "修改计划文件" : "完成计划文件实现";
      List<String> tools = "create".equals(action)
          ? Collections.singletonList("create_file")
          : "edit".equals(action)
              ? Arrays.asList("search_replace", "rewrite")
              : Collections.emptyList();
      Map<String, Object> step = step("implement-" + action, title, "implement", tools);
      step.put("file_refs", group.getValue());
      generated.add(step);
    }
    steps.addAll(insertAt, generated);
  }

  @SuppressWarnings("unchecked")
  private static void ensureAllFilesMapped(
      List<Map<String, Object>> implementation, List<Map<String, Object>> files) {
    if (implementation.isEmpty()) return;
    Set<String> assigned = new LinkedHashSet<>();
    for (Map<String, Object> step : implementation) {
      assigned.addAll((List<String>) step.get("file_refs"));
    }
    for (Map<String, Object> file : files) {
      String id = text(file.get("file_id"));
      if (assigned.contains(id)) continue;
      Map<String, Object> target = implementation.get(0);
      for (Map<String, Object> candidate : implementation) {
        if (((List<?>) candidate.get("file_refs")).size()
            < ((List<?>) target.get("file_refs")).size()) target = candidate;
      }
      ((List<String>) target.get("file_refs")).add(id);
      assigned.add(id);
    }
  }

  private static List<String> fileIds(List<Map<String, Object>> files) {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> file : files) out.add(text(file.get("file_id")));
    return out;
  }

  private static NormalizedInteractionChecks normalizeInteractionChecks(Object raw) {
    NormalizedInteractionChecks normalized = new NormalizedInteractionChecks();
    List<?> values = raw instanceof List ? (List<?>) raw
        : text(raw).isEmpty() ? Collections.emptyList() : Collections.singletonList(raw);
    Set<String> ids = new LinkedHashSet<>();
    int requestedRequired = 0;
    int dropped = 0;
    for (Object value : values) {
      Map<?, ?> map = value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
      String description = value instanceof Map
          ? first(map, "description", "title", "name", "check", "assertion", "expected", "action")
          : text(value);
      if (normalized.checks.size() >= 5) {
        dropped++;
        String omitted = limit(description, 72);
        if (!omitted.isEmpty() && normalized.warnings.size() < 4) {
          normalized.warnings.add("未纳入硬门禁的建议检查：" + omitted);
        }
        continue;
      }
      description = limit(description, 160);
      if (description.isEmpty()) continue;
      String requested = limit(first(map, "check_id", "id"), 64);
      String id = validIdentifier(requested) ? requested : stableCheckId(description);
      id = uniqueId(id, ids);
      String action = limit(first(map, "action"), 40);
      String observable = limit(first(map, "observable_state", "observable"), 120);
      String expected = limit(first(map, "expected_change", "expected"), 120);
      String setup = limit(first(map, "deterministic_setup", "setup"), 120);
      boolean wantsRequired = value instanceof Map && bool(map.get("required"));
      boolean completeContract = !action.isEmpty()
          && !observable.isEmpty()
          && !expected.isEmpty()
          && !setup.isEmpty();
      boolean required = wantsRequired && completeContract && requestedRequired < 3;
      String advisoryReason = limit(first(map, "advisory_reason", "waive_reason"), 160);
      if (wantsRequired && !completeContract) {
        advisoryReason = "missing_deterministic_contract";
      } else if (wantsRequired && requestedRequired >= 3) {
        advisoryReason = "required_limit";
      } else if (!wantsRequired && advisoryReason.isEmpty()) {
        advisoryReason = value instanceof Map ? "non_blocking_check" : "legacy_string_check";
      }
      if (required) requestedRequired++;
      Map<String, Object> check = new LinkedHashMap<>();
      check.put("check_id", id);
      check.put("description", description);
      check.put("required", required);
      if (!action.isEmpty()) check.put("action", action);
      if (!observable.isEmpty()) check.put("observable_state", observable);
      if (!expected.isEmpty()) check.put("expected_change", expected);
      if (!setup.isEmpty()) check.put("deterministic_setup", setup);
      if (!required) check.put("advisory_reason", advisoryReason);
      normalized.checks.add(check);
    }
    if (dropped > 0) normalized.warnings.add("interaction_checks 超过5项，已省略" + dropped + "项");
    return normalized;
  }

  private static int requiredCount(List<Map<String, Object>> checks) {
    int count = 0;
    for (Map<String, Object> check : checks) if (Boolean.TRUE.equals(check.get("required"))) count++;
    return count;
  }

  private static Map<String, Object> coreInteractionCheck() {
    Map<String, Object> check = new LinkedHashMap<>();
    check.put("check_id", "core_interaction");
    check.put("description", "执行一次核心用户操作并验证可观察状态发生变化");
    check.put("required", true);
    check.put("action", "click_or_input");
    check.put("observable_state", "visible_or_accessible_state");
    check.put("expected_change", "操作后状态与操作前不同");
    check.put("deterministic_setup", "页面初始状态可加载并可观察");
    return check;
  }

  private static String stableCheckId(String description) {
    String normalized = text(description).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder("check-");
      for (int index = 0; index < 6; index++) {
        int value = digest[index] & 0xff;
        if (value < 16) hex.append('0');
        hex.append(Integer.toHexString(value));
      }
      return hex.toString();
    } catch (Exception ignored) {
      return "check-" + Integer.toHexString(normalized.hashCode());
    }
  }

  private static final class NormalizedInteractionChecks {
    final List<Map<String, Object>> checks = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
  }

  private static void addDefaults(
      List<Map<String, Object>> steps, String qualityMode, List<String> verificationTools) {
    List<Map<String, Object>> defaults = Arrays.asList(
        step("discover", "读取并确认真实上下文", "discover", Collections.emptyList()),
        step("implement", "完成核心实现与接入", "implement", Collections.emptyList()),
        step("verify", "执行真实验证", "verify", verificationTools),
        step("quality", "提交结构化质量自查", "quality", Collections.singletonList("quality_review")));
    if (steps.isEmpty()) {
      steps.addAll(defaults.subList(0, "interface_product".equals(qualityMode) ? 4 : 3));
      return;
    }
    Set<String> phases = phases(steps);
    for (Map<String, Object> candidate : defaults) {
      String phase = text(candidate.get("phase"));
      if ("quality".equals(phase) && !"interface_product".equals(qualityMode)) continue;
      if (phases.add(phase)) steps.add(candidate);
    }
    while (steps.size() > MAX_STEPS) mergeDuplicatePhase(steps);
  }

  @SuppressWarnings("unchecked")
  private static void mergeDuplicatePhase(List<Map<String, Object>> steps) {
    for (int right = steps.size() - 1; right > 0; right--) {
      String phase = text(steps.get(right).get("phase"));
      for (int left = 0; left < right; left++) {
        if (!phase.equals(text(steps.get(left).get("phase")))) continue;
        Map<String, Object> target = steps.get(left);
        Map<String, Object> source = steps.remove(right);
        mergeList((List<String>) target.get("file_refs"), source.get("file_refs"), 8);
        mergeList((List<String>) target.get("required_tools"), source.get("required_tools"), 4);
        return;
      }
    }
    steps.remove(steps.size() - 1);
  }

  private static void mergeList(List<String> target, Object raw, int max) {
    if (target == null || !(raw instanceof List)) return;
    for (Object value : (List<?>) raw) {
      String item = text(value);
      if (!item.isEmpty() && !target.contains(item) && target.size() < max) target.add(item);
    }
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
    out.put("file_refs", new ArrayList<>());
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

  private static boolean bool(Object value) {
    return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(text(value));
  }

  private static boolean validIdentifier(String value) {
    return !value.isEmpty() && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  }

  private static String fileId(String path) {
    String normalized = normalizedPath(path);
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int index = 0; index < 6; index++) {
        int value = digest[index] & 0xff;
        if (value < 16) hex.append('0');
        hex.append(Integer.toHexString(value));
      }
      return "file-" + hex;
    } catch (Exception ignored) {
      return "file-" + Integer.toHexString(normalized.hashCode());
    }
  }

  private static String normalizedPath(String value) {
    String out = text(value).replace('\\', '/');
    while (out.startsWith("./")) out = out.substring(2);
    while (out.contains("//")) out = out.replace("//", "/");
    return out;
  }
}
