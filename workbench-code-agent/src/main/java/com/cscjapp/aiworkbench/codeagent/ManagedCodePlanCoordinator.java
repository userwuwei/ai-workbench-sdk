package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

/** Run-scoped managed plan shared by Code Agent tools and completion validation. */
final class ManagedCodePlanCoordinator implements ToolPolicy, AgentRunLifecycle {
  private final CodePlanningMode mode;
  private final CodeValidationContract contract;
  private final Map<String, CodeToolRole> roles;
  private final CodePlanNormalizer normalizer;
  private long generation;
  private long activeRunId = -1L;
  private long planSequence;
  private String planId = "";
  private Map<String, Object> normalizedPlan;
  private List<PlanStep> steps = new ArrayList<>();
  private final List<Evidence> evidence = new ArrayList<>();
  private Map<String, Object> currentState = Collections.emptyMap();
  private boolean stateChanged;

  ManagedCodePlanCoordinator(
      CodePlanningMode mode,
      CodeValidationContract contract,
      Map<String, CodeToolRole> roles) {
    this.mode = mode == null ? CodePlanningMode.ADAPTIVE : mode;
    this.contract = contract;
    this.roles = new LinkedHashMap<>(roles);
    normalizer = new CodePlanNormalizer(contract);
  }

  @Override
  public synchronized void onRunStarted(AgentRunContext context) {
    generation++;
    activeRunId = context == null ? -1L : context.runId();
    planId = "";
    normalizedPlan = null;
    steps = new ArrayList<>();
    evidence.clear();
    currentState = Collections.emptyMap();
    stateChanged = false;
  }

  @Override
  public synchronized void onRunFinished(AgentRunContext context, String state) {
    if (context == null || context.runId() != activeRunId) return;
    generation++;
    activeRunId = -1L;
    planId = "";
    normalizedPlan = null;
    steps = new ArrayList<>();
    evidence.clear();
    currentState = Collections.emptyMap();
    stateChanged = false;
  }

  @Override
  public synchronized boolean supports(ToolInvocation invocation) {
    if (invocation == null || mode != CodePlanningMode.FORCE || hasPlan()) return false;
    CodeToolRole role = role(invocation.tool().spec().name());
    return role == CodeToolRole.CREATE || role == CodeToolRole.EDIT;
  }

  @Override
  public Cancellable evaluate(
      ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
    callback.resolve(ToolPolicyDecision.error(
        "managed_plan_required",
        "当前任务要求在首次写入前调用 plan_task 建立短计划。",
        true));
    return Cancellable.NONE;
  }

  synchronized long generation() {
    return generation;
  }

  synchronized Map<String, Object> acceptPlan(Map<String, ?> arguments) {
    Map<String, Object> next = normalizer.normalize(arguments);
    if (normalizedPlan != null && text(next.get("replan_reason")).isEmpty()) {
      next.put("replan_reason", "基于当前任务的新证据调整剩余步骤");
    }
    normalizedPlan = next;
    planId = "plan-" + activeRunId + "-" + (++planSequence);
    steps = decodeSteps(next.get("steps"));
    recompute();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operation", CodeAgentToolNames.PLAN_TASK);
    result.put("normalized_plan_version", CodePlanNormalizer.VERSION);
    result.put("normalized_plan", new ToolArguments(next).asMap());
    result.put("plan_state", state());
    stateChanged = false;
    return result;
  }

  synchronized ToolResult recordAndDecorate(
      long capturedGeneration,
      String toolName,
      ToolResult result) {
    if (capturedGeneration != generation || result == null) return result;
    if (!CodeAgentToolNames.PLAN_TASK.equals(toolName)) record(toolName, result);
    if (!stateChanged || !hasPlan() || !result.isSuccess()) return result;
    Map<String, Object> data = new LinkedHashMap<>(result.data());
    data.put("plan_state", state());
    stateChanged = false;
    return ToolResult.success(data);
  }

  synchronized boolean hasPlan() {
    return normalizedPlan != null && !steps.isEmpty();
  }

  synchronized boolean isComplete() {
    if (!hasPlan()) return false;
    for (PlanStep step : steps) if (!step.done) return false;
    return true;
  }

  synchronized String qualityMode() {
    return normalizedPlan == null ? "" : text(normalizedPlan.get("quality_mode"));
  }

  synchronized boolean hasCurrentEvidence(String toolName) {
    return hasTool(toolName);
  }

  CodePlanningMode mode() {
    return mode;
  }

  private void record(String toolName, ToolResult result) {
    CodeToolRole role = role(toolName);
    if (!validEvidence(role, result)) return;
    if (role == CodeToolRole.CREATE || role == CodeToolRole.EDIT) {
      evidence.removeIf(item -> item.role == CodeToolRole.VERIFY || item.role == CodeToolRole.QUALITY);
    }
    evidence.add(new Evidence(toolName, role));
    recompute();
  }

  private boolean validEvidence(CodeToolRole role, ToolResult result) {
    if (role == null || !result.isSuccess()) return false;
    Map<String, Object> data = result.data();
    if (Boolean.FALSE.equals(data.get("passed"))) return false;
    if (role == CodeToolRole.READ) return hasReadContent(data);
    if (role == CodeToolRole.CREATE || role == CodeToolRole.EDIT) {
      return !Boolean.FALSE.equals(data.get("changed"));
    }
    if (role == CodeToolRole.VERIFY) return Boolean.TRUE.equals(data.get("passed"));
    if (role == CodeToolRole.QUALITY) {
      return Boolean.TRUE.equals(data.get("passed"))
          && !Boolean.TRUE.equals(data.get("minimal_version_risk"))
          && emptyValue(data.get("blocking_gaps"))
          && emptyValue(data.get("claimed_but_unsupported"));
    }
    return role == CodeToolRole.DISCOVER;
  }

  private static boolean hasReadContent(Map<String, Object> data) {
    if (data.containsKey("content") || nonEmptyList(data.get("read_paths"))) return true;
    Object items = data.get("items");
    if (!(items instanceof List)) return false;
    for (Object raw : (List<?>) items) {
      if (!(raw instanceof Map)) continue;
      Object nested = ((Map<?, ?>) raw).get("result");
      if (!(nested instanceof Map)) continue;
      Map<?, ?> value = (Map<?, ?>) nested;
      if (value.containsKey("content") || value.containsKey("path") || value.containsKey("resolved_path")) {
        return true;
      }
    }
    return false;
  }

  private void recompute() {
    for (PlanStep step : steps) step.done = satisfied(step);
    Map<String, Object> next = buildState();
    if (!next.equals(currentState)) {
      currentState = next;
      stateChanged = true;
    }
  }

  private boolean satisfied(PlanStep step) {
    if (!step.requiredTools.isEmpty()) {
      for (String required : step.requiredTools) if (!hasTool(required)) return false;
      return true;
    }
    for (Evidence item : evidence) {
      if ("discover".equals(step.phase)
          && (item.role == CodeToolRole.READ || item.role == CodeToolRole.DISCOVER)) return true;
      if ("implement".equals(step.phase)
          && (item.role == CodeToolRole.CREATE || item.role == CodeToolRole.EDIT)) return true;
      if ("verify".equals(step.phase) && item.role == CodeToolRole.VERIFY) return true;
      if ("quality".equals(step.phase) && item.role == CodeToolRole.QUALITY) return true;
    }
    return false;
  }

  private boolean hasTool(String toolName) {
    String expected = canonicalToolName(toolName);
    for (Evidence item : evidence) if (expected.equals(item.toolName)) return true;
    return false;
  }

  private String canonicalToolName(String raw) {
    String value = text(raw);
    if (roles.containsKey(value)) return value;
    for (char separator : new char[] {':', '：', ' ', '.'}) {
      int index = value.indexOf(separator);
      if (index > 0) {
        String candidate = value.substring(0, index).trim();
        if (roles.containsKey(candidate)) return candidate;
      }
    }
    return value;
  }

  private Map<String, Object> buildState() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("plan_id", planId);
    List<String> done = new ArrayList<>();
    PlanStep current = null;
    for (PlanStep step : steps) {
      if (step.done) done.add(limit(step.id, 40));
      else if (current == null) current = step;
    }
    if (current == null) {
      state.put("current_step", Collections.emptyMap());
      state.put("missing_evidence", Collections.emptyList());
    } else {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", limit(current.id, 40));
      item.put("title", limit(current.title, 80));
      state.put("current_step", item);
      state.put("missing_evidence", missing(current));
    }
    state.put("done_steps", done);
    return state;
  }

  private List<String> missing(PlanStep step) {
    List<String> out = new ArrayList<>();
    if (!step.requiredTools.isEmpty()) {
      for (String tool : step.requiredTools) {
        if (!hasTool(tool)) out.add(limit(canonicalToolName(tool), 64));
      }
      return out;
    }
    out.add("discover".equals(step.phase) ? "read_evidence"
        : "implement".equals(step.phase) ? "write_evidence"
        : "verify".equals(step.phase) ? "verification_evidence"
        : "quality_review");
    return out;
  }

  private Map<String, Object> state() {
    return new ToolArguments(currentState).asMap();
  }

  private CodeToolRole role(String toolName) {
    return roles.get(toolName);
  }

  private static List<PlanStep> decodeSteps(Object raw) {
    List<PlanStep> out = new ArrayList<>();
    if (!(raw instanceof List)) return out;
    for (Object item : (List<?>) raw) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> map = (Map<?, ?>) item;
      out.add(new PlanStep(
          text(map.get("id")), text(map.get("title")), text(map.get("phase")),
          stringList(map.get("required_tools"))));
    }
    return out;
  }

  private static List<String> stringList(Object raw) {
    List<String> out = new ArrayList<>();
    if (raw instanceof List) for (Object value : (List<?>) raw) {
      String item = text(value);
      if (!item.isEmpty()) out.add(item);
    }
    return out;
  }

  private static boolean nonEmptyList(Object value) {
    return value instanceof List && !((List<?>) value).isEmpty();
  }

  private static boolean emptyValue(Object value) {
    if (value == null) return true;
    if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
    if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
    return text(value).isEmpty();
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String limit(String value, int maxLength) {
    String safe = text(value);
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
  }

  private static final class Evidence {
    final String toolName;
    final CodeToolRole role;
    Evidence(String toolName, CodeToolRole role) {
      this.toolName = toolName;
      this.role = role;
    }
  }

  private static final class PlanStep {
    final String id;
    final String title;
    final String phase;
    final List<String> requiredTools;
    boolean done;
    PlanStep(String id, String title, String phase, List<String> requiredTools) {
      this.id = id;
      this.title = title;
      this.phase = phase;
      this.requiredTools = requiredTools;
    }
  }
}
