package com.cscjapp.aiworkbench.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small evidence tracker for plan presentation. It deliberately does not own agent execution. */
final class WorkbenchPlanTracker {
  static final class Step {
    final String id;
    final String title;
    final String phase;
    final List<String> requiredTools;
    final Set<String> satisfiedTools = new LinkedHashSet<>();
    String status;

    Step(String id, String title, String phase, List<String> requiredTools, String status) {
      this.id = id;
      this.title = title;
      this.phase = phase;
      this.requiredTools = new ArrayList<>(requiredTools);
      this.status = normalizeStatus(status);
    }

    Map<String, Object> toMap() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("id", id);
      value.put("title", title);
      value.put("phase", phase);
      value.put("required_tools", new ArrayList<>(requiredTools));
      value.put("satisfied_tools", new ArrayList<>(satisfiedTools));
      value.put("status", status);
      return value;
    }
  }

  private final List<Step> steps = new ArrayList<>();
  private boolean active;

  void clear() {
    steps.clear();
    active = false;
  }

  void load(Object raw) {
    clear();
    if (raw instanceof List) {
      int index = 0;
      for (Object item : (List<?>) raw) {
        index++;
        Step step = parseStep(item, index);
        if (step != null) steps.add(step);
      }
    }
    active = !steps.isEmpty();
    selectCurrent();
  }

  void restore(Object raw) {
    load(raw);
    if (!(raw instanceof List)) return;
    for (int i = 0; i < steps.size() && i < ((List<?>) raw).size(); i++) {
      Object item = ((List<?>) raw).get(i);
      if (!(item instanceof Map)) continue;
      steps.get(i).satisfiedTools.addAll(strings(((Map<?, ?>) item).get("satisfied_tools")));
      if ("running".equals(steps.get(i).status)) steps.get(i).status = "pending";
    }
    active = false;
  }

  void restoreLegacy(Object labels, Object states) {
    clear();
    if (!(labels instanceof List)) return;
    List<?> statusList = states instanceof List ? (List<?>) states : Collections.emptyList();
    for (int i = 0; i < ((List<?>) labels).size(); i++) {
      String title = text(((List<?>) labels).get(i));
      if (title.isEmpty()) continue;
      String status = i < statusList.size() ? text(statusList.get(i)) : "pending";
      if ("running".equals(status)) status = "pending";
      steps.add(new Step("legacy-" + (i + 1), title, inferPhase(title), Collections.<String>emptyList(), status));
    }
    active = false;
  }

  boolean recordTool(String toolName) {
    if (!active || toolName == null || "finalize_task".equals(toolName)) return false;
    boolean changed = false;
    boolean exactMatch = false;
    for (Step step : steps) {
      if (isFinished(step.status) || !step.requiredTools.contains(toolName)) continue;
      exactMatch = true;
      changed |= step.satisfiedTools.add(toolName);
      if (step.satisfiedTools.containsAll(step.requiredTools)) {
        step.status = "done";
        changed = true;
      }
    }
    if (!exactMatch) {
      String phase = phaseForTool(toolName);
      for (Step step : steps) {
        if (!isFinished(step.status)
            && step.requiredTools.isEmpty()
            && phase.equals(step.phase)) {
          step.status = "done";
          changed = true;
          break;
        }
      }
    }
    if (changed) selectCurrent();
    return changed;
  }

  void complete() {
    for (Step step : steps) step.status = "done";
    active = false;
  }

  void deactivate() {
    for (Step step : steps) if ("running".equals(step.status)) step.status = "pending";
    active = false;
  }

  boolean isEmpty() {
    return steps.isEmpty();
  }

  String currentTitle() {
    for (Step step : steps) if ("running".equals(step.status)) return step.title;
    for (Step step : steps) if ("pending".equals(step.status)) return step.title;
    return steps.isEmpty() ? "" : "已完成";
  }

  String currentPhase() {
    for (Step step : steps) if ("running".equals(step.status)) return step.phase;
    for (Step step : steps) if ("pending".equals(step.status)) return step.phase;
    return steps.isEmpty() ? "" : "finalize";
  }

  List<String> displayLines() {
    List<String> lines = new ArrayList<>();
    for (Step step : steps) lines.add(marker(step.status) + step.title);
    return lines;
  }

  List<Map<String, Object>> snapshot() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Step step : steps) result.add(step.toMap());
    return result;
  }

  List<String> labels() {
    List<String> result = new ArrayList<>();
    for (Step step : steps) result.add(step.title);
    return result;
  }

  List<String> states() {
    List<String> result = new ArrayList<>();
    for (Step step : steps) result.add(step.status);
    return result;
  }

  private void selectCurrent() {
    if (!active) return;
    boolean hasRunning = false;
    for (Step step : steps) {
      if ("running".equals(step.status)) {
        if (!hasRunning) hasRunning = true;
        else step.status = "pending";
      }
    }
    if (hasRunning) return;
    for (Step step : steps) {
      if ("pending".equals(step.status)) {
        step.status = "running";
        return;
      }
    }
  }

  private static Step parseStep(Object raw, int index) {
    if (raw instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) raw;
      String title = first(map, "title", "action", "description", "name");
      String legacyStep = text(map.get("step"));
      if (title.isEmpty() && !legacyStep.matches("[0-9]+")) title = legacyStep;
      if (title.isEmpty()) return null;
      String phase = text(map.get("phase")).toLowerCase(Locale.ROOT);
      if (phase.isEmpty()) phase = inferPhase(title);
      String id = first(map, "id");
      if (id.isEmpty()) id = "step-" + index;
      return new Step(id, title, phase, strings(map.get("required_tools")),
          first(map, "status", "state"));
    }
    String title = text(raw);
    if (title.isEmpty() || title.matches("[0-9]+")) return null;
    return new Step("step-" + index, title, inferPhase(title),
        Collections.<String>emptyList(), "pending");
  }

  private static String phaseForTool(String tool) {
    if (tool.startsWith("read") || "list_dir".equals(tool) || "search_files".equals(tool)) {
      return "discover";
    }
    if ("create_file".equals(tool) || "search_replace".equals(tool) || "rewrite".equals(tool)) {
      return "implement";
    }
    if ("quality_review".equals(tool)) return "quality";
    if (tool.endsWith("_test") || tool.endsWith("_check") || tool.startsWith("verify")) {
      return "verify";
    }
    return "";
  }

  private static String inferPhase(String title) {
    String lower = title.toLowerCase(Locale.ROOT);
    if (lower.contains("quality") || title.contains("质量") || title.contains("审查")) return "quality";
    if (lower.contains("final") || title.contains("结束") || title.contains("收口")) return "finalize";
    if (lower.contains("test") || lower.contains("check") || title.contains("验证") || title.contains("检查")) return "verify";
    if (title.contains("读取") || title.contains("分析") || title.contains("确认")) return "discover";
    return "implement";
  }

  private static String normalizeStatus(String value) {
    if ("done".equals(value) || "completed".equals(value) || "success".equals(value)) return "done";
    if ("running".equals(value) || "active".equals(value) || "in_progress".equals(value)) return "running";
    if ("skipped".equals(value)) return "skipped";
    return "pending";
  }

  private static boolean isFinished(String status) {
    return "done".equals(status) || "skipped".equals(status);
  }

  private static String marker(String status) {
    if ("done".equals(status)) return "✓ ";
    if ("running".equals(status)) return "● ";
    if ("skipped".equals(status)) return "— ";
    return "○ ";
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

  private static String first(Map<?, ?> map, String... keys) {
    for (String key : keys) {
      String value = text(map.get(key));
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}
