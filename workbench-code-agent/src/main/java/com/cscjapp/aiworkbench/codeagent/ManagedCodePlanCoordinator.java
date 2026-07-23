package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.*;
import java.io.File;
import java.util.*;

/** Run-scoped managed plan shared by Code Agent tools and completion validation. */
final class ManagedCodePlanCoordinator implements ToolPolicy, AgentRunLifecycle {
  private static final int MAX_STATE_CHARS = 800;
  private final CodePlanningMode mode;
  private final CodeValidationContract contract;
  private final Map<String, CodeToolRole> roles;
  private final WorkspaceAccess workspace;
  private final CodePlanNormalizer normalizer;
  private long generation;
  private long activeRunId = -1L;
  private long planSequence;
  private long revision;
  private String planId = "";
  private Map<String, Object> normalizedPlan;
  private List<PlanFile> files = new ArrayList<>();
  private List<PlanStep> steps = new ArrayList<>();
  private final List<Evidence> evidence = new ArrayList<>();
  private final Map<String, Long> pathRevisions = new LinkedHashMap<>();
  private final Map<String, String> createdFileBindings = new LinkedHashMap<>();
  private final Set<String> unresolvedWritePaths = new LinkedHashSet<>();
  private final Map<String, Long> interactionEvidence = new LinkedHashMap<>();
  private final Map<String, Set<String>> failedInteractionVariants = new LinkedHashMap<>();
  private final Set<String> failedInvocationSignatures = new LinkedHashSet<>();
  private String nextAction = "";
  private Map<String, Object> currentState = Collections.emptyMap();
  private boolean stateChanged;

  ManagedCodePlanCoordinator(
      CodePlanningMode mode,
      CodeValidationContract contract,
      Map<String, CodeToolRole> roles) {
    this(mode, contract, roles, null);
  }

  ManagedCodePlanCoordinator(
      CodePlanningMode mode,
      CodeValidationContract contract,
      Map<String, CodeToolRole> roles,
      WorkspaceAccess workspace) {
    this.mode = mode == null ? CodePlanningMode.ADAPTIVE : mode;
    this.contract = contract;
    this.roles = new LinkedHashMap<>(roles);
    this.workspace = workspace;
    normalizer = new CodePlanNormalizer(contract);
  }

  @Override
  public synchronized void onRunStarted(AgentRunContext context) {
    generation++;
    activeRunId = context == null ? -1L : context.runId();
    revision = 0L;
    planId = "";
    normalizedPlan = null;
    files = new ArrayList<>();
    steps = new ArrayList<>();
    evidence.clear();
    pathRevisions.clear();
    createdFileBindings.clear();
    unresolvedWritePaths.clear();
    interactionEvidence.clear();
    failedInteractionVariants.clear();
    failedInvocationSignatures.clear();
    nextAction = "";
    currentState = Collections.emptyMap();
    stateChanged = false;
  }

  @Override
  public synchronized void onRunFinished(AgentRunContext context, String state) {
    if (context == null || context.runId() != activeRunId) return;
    generation++;
    activeRunId = -1L;
    revision = 0L;
    planId = "";
    normalizedPlan = null;
    files = new ArrayList<>();
    steps = new ArrayList<>();
    evidence.clear();
    pathRevisions.clear();
    createdFileBindings.clear();
    unresolvedWritePaths.clear();
    interactionEvidence.clear();
    failedInteractionVariants.clear();
    failedInvocationSignatures.clear();
    nextAction = "";
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
    Map<String, Object> previous = normalizedPlan;
    Map<String, Object> next = normalizer.normalize(arguments);
    if (previous != null && text(next.get("replan_reason")).isEmpty()) {
      next.put("replan_reason", "基于当前任务的新证据调整剩余步骤");
    }
    appendWaivedChecks(previous, next);
    normalizedPlan = next;
    planId = "plan-" + activeRunId + "-" + (++planSequence);
    files = decodeFiles(next.get("planned_files"));
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

  synchronized ToolResult duplicateAttempt(String toolName, ToolArguments arguments) {
    String signature = invocationSignature(toolName, arguments);
    if (signature.isEmpty() || !failedInvocationSignatures.contains(signature)) return null;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("operation", toolName);
    data.put("passed", false);
    data.put("failure_class", "duplicate_attempt_no_new_evidence");
    data.put("next_action", nextAction.isEmpty() ? "change_strategy" : nextAction);
    if (hasPlan()) data.put("plan_state", state());
    return ToolResult.error(
        "duplicate_attempt_no_new_evidence",
        "当前文件版本已执行过完全相同且失败的调用；请修改选择器、步骤、代码或重新规划不可验证检查。",
        true,
        data);
  }

  synchronized ToolResult executeMeta(String toolName, ToolArguments arguments) {
    if (CodeAgentToolNames.PLAN_TASK.equals(toolName)) {
      return ToolResult.success(acceptPlan(arguments.asMap()));
    }
    Map<String, Object> data = new LinkedHashMap<>(arguments.asMap());
    data.put("operation", toolName);
    if (CodeAgentToolNames.QUALITY_REVIEW.equals(toolName) && hasPlan()) {
      List<String> missing = missingRequiredInteractionIds();
      List<String> evidenceMissing = missingBeforeQuality();
      List<String> claimed = stringList(data.get("covered_interaction_check_ids"));
      List<String> covered = coveredRequiredInteractionIds();
      List<String> unsupported = new ArrayList<>();
      for (String claim : claimed) if (!covered.contains(claim)) unsupported.add(claim);
      if (!unsupported.isEmpty()) {
        data.put("passed", false);
        data.put("unsupported_claims", unsupported);
        data.put("covered_interaction_check_ids", covered);
        data.put("next_action", "remove_unsupported_quality_claims");
        data.put("plan_state", state());
        return ToolResult.error(
            "quality_claims_unsupported",
            "质量审查引用了当前 revision 未覆盖的交互检查：" + String.join(", ", unsupported),
            true,
            data);
      }
      if (!missing.isEmpty() || !evidenceMissing.isEmpty()) {
        data.put("passed", false);
        data.put("missing_required_checks", missing);
        data.put("missing_evidence", evidenceMissing);
        data.put("covered_interaction_check_ids", covered);
        data.put("next_action", nextActionFor(missing, evidenceMissing));
        data.put("plan_state", state());
        return ToolResult.error(
            "quality_evidence_incomplete",
            "质量审查前仍缺少当前文件版本的真实证据：" + joinMissing(missing, evidenceMissing),
            true,
            data);
      }
      data.put("covered_interaction_check_ids", covered);
      mergeAdvisoryWarnings(data);
    }
    if (CodeAgentToolNames.FINALIZE_TASK.equals(toolName)
        && "completed".equals(text(data.get("status")))) {
      String completionType = text(data.get("completion_type"));
      if (completionType.isEmpty()) completionType = "code_generation";
      boolean planRequired = mode == CodePlanningMode.FORCE
          || (mode != CodePlanningMode.SKIP && contract.requiresManagedPlan(completionType));
      List<String> missing = missingRequiredInteractionIds();
      List<String> evidenceMissing = missingBeforeQuality();
      if (planRequired && !hasPlan()) evidenceMissing.add(0, "plan:plan_task");
      if (hasPlan() && !hasRoleAtRevision(CodeToolRole.QUALITY) && requiresQuality()) {
        evidenceMissing.add("quality:quality_review");
      }
      if (!missing.isEmpty() || !evidenceMissing.isEmpty() || (hasPlan() && !isComplete())) {
        data.put("passed", false);
        data.put("missing_required_checks", missing);
        data.put("missing_evidence", unique(evidenceMissing));
        data.put("next_action", nextActionFor(missing, evidenceMissing));
        if (hasPlan()) data.put("plan_state", state());
        return ToolResult.error(
            "completion_evidence_incomplete",
            "任务尚未具备完成条件：" + joinMissing(missing, evidenceMissing),
            true,
            data);
      }
      mergeAdvisoryWarnings(data);
    }
    return ToolResult.success(data);
  }

  synchronized ToolResult recordAndDecorate(
      long capturedGeneration,
      String toolName,
      ToolArguments arguments,
      ToolResult result) {
    if (capturedGeneration != generation || result == null) return result;
    if (!CodeAgentToolNames.PLAN_TASK.equals(toolName)) {
      record(toolName, arguments == null ? ToolArguments.empty() : arguments, result);
    }
    if (!stateChanged || !hasPlan()) return result;
    Map<String, Object> data = new LinkedHashMap<>(result.data());
    data.put("plan_state", state());
    stateChanged = false;
    if (result.isSuccess()) return ToolResult.success(data);
    if (result.status() == ToolResult.Status.ERROR) {
      return ToolResult.error(result.errorCode(), result.message(), result.retryable(), data);
    }
    return result;
  }

  /** Kept package-compatible for tests and integrations compiled against the V2 internals. */
  synchronized ToolResult recordAndDecorate(
      long capturedGeneration, String toolName, ToolResult result) {
    return recordAndDecorate(capturedGeneration, toolName, ToolArguments.empty(), result);
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
    return hasCurrentTool(toolName);
  }

  CodePlanningMode mode() {
    return mode;
  }

  private void record(String toolName, ToolArguments arguments, ToolResult result) {
    CodeToolRole role = role(toolName);
    if (role == CodeToolRole.VERIFY && "browser_test".equals(toolName)) {
      recordInteractionAudit(arguments, result);
    }
    if (!validEvidence(role, result)) {
      recordFailedInvocation(toolName, arguments, result);
    } else {
      nextAction = "";
    }
    if (role == CodeToolRole.CREATE || role == CodeToolRole.EDIT) {
      recordWrite(toolName, role, arguments, result);
      return;
    }
    if (role == CodeToolRole.VERIFY || role == CodeToolRole.QUALITY) {
      boolean valid = validEvidence(role, result);
      boolean removed = evidence.removeIf(item ->
          item.generation == generation
              && item.revision == revision
              && (role == CodeToolRole.QUALITY ? item.role == role : toolName.equals(item.toolName)));
      if (!valid) {
        if (removed) recompute();
        return;
      }
    }
    if (!validEvidence(role, result)) return;
    if (role == CodeToolRole.READ) {
      List<String> paths = evidencePaths(role, arguments, result.data());
      if (paths.isEmpty()) return;
      for (String path : paths) {
        evidence.add(new Evidence(
            toolName,
            role,
            path,
            "",
            generation,
            pathRevisions.getOrDefault(path, 0L),
            true));
      }
    } else {
      evidence.add(new Evidence(toolName, role, "", "", generation, revision, true));
    }
    recompute();
  }

  private void recordWrite(
      String toolName, CodeToolRole role, ToolArguments arguments, ToolResult result) {
    Map<String, Object> data = result.data();
    ResolvedWrite write = resolveWriteEvidence(role, arguments, data);
    if (write == null) return;
    boolean partial = hasPartialWrite(data);
    boolean validSuccess = result.isSuccess()
        && !partial
        && !Boolean.FALSE.equals(data.get("passed"))
        && !Boolean.FALSE.equals(data.get("changed"));
    boolean mutated = writeMutated(data) || validSuccess;
    if (!validSuccess) {
      // A failed or partially-applied write is not completion evidence. If it did mutate the
      // file, however, it creates a new revision: previous verification/quality evidence is
      // stale and the file remains unresolved until a later complete write succeeds.
      if (!mutated) return;
      advanceRevision(write.path);
      unresolvedWritePaths.add(write.path);
      recompute();
      return;
    }
    advanceRevision(write.path);
    unresolvedWritePaths.remove(write.path);
    evidence.removeIf(item -> item.role == role && write.path.equals(item.path));
    evidence.add(
        new Evidence(
            toolName,
            role,
            write.path,
            write.planFileId,
            generation,
            revision,
            true));
    recompute();
  }

  private void advanceRevision(String path) {
    revision++;
    evidence.removeIf(item -> item.role == CodeToolRole.VERIFY || item.role == CodeToolRole.QUALITY);
    interactionEvidence.clear();
    failedInteractionVariants.clear();
    failedInvocationSignatures.clear();
    nextAction = "";
    pathRevisions.put(path, revision);
  }

  private boolean validEvidence(CodeToolRole role, ToolResult result) {
    if (role == null || !result.isSuccess()) return false;
    Map<String, Object> data = result.data();
    if (Boolean.FALSE.equals(data.get("passed"))) return false;
    if (role == CodeToolRole.READ) return hasReadContent(data);
    if (role == CodeToolRole.VERIFY) return Boolean.TRUE.equals(data.get("passed"));
    if (role == CodeToolRole.QUALITY) {
      return Boolean.TRUE.equals(data.get("passed"))
          && !Boolean.TRUE.equals(data.get("minimal_version_risk"))
          && emptyValue(data.get("blocking_gaps"))
          && emptyValue(data.get("claimed_but_unsupported"));
    }
    return role == CodeToolRole.DISCOVER;
  }

  private List<String> evidencePaths(
      CodeToolRole role, ToolArguments arguments, Map<String, Object> data) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (role == CodeToolRole.READ) {
      String requested = text(arguments.get("path"));
      if (!requested.isEmpty() && canonicalPath(requested, true).isEmpty()) return new ArrayList<>();
      if (data.containsKey("content")) {
        String requestedFile = canonicalEvidenceFile(requested);
        Object resultPath = firstValue(data, "resolved_path", "path");
        String returnedFile = resultPath == null ? requestedFile : canonicalEvidenceFile(text(resultPath));
        if (requestedFile.isEmpty() || returnedFile.isEmpty() || !requestedFile.equals(returnedFile)) {
          return new ArrayList<>();
        }
        out.add(requestedFile);
        return new ArrayList<>(out);
      }
      collectReadPaths(data, out);
      return new ArrayList<>(out);
    }
    return new ArrayList<>();
  }

  private ResolvedWrite resolveWriteEvidence(
      CodeToolRole role, ToolArguments arguments, Map<String, Object> data) {
    String invocationPath = canonicalEvidenceFile(text(arguments.get("path")));
    if (invocationPath.isEmpty()) return null;
    Object resultPath = firstValue(data, "resolved_path", "path");
    String returned = resultPath == null ? invocationPath : canonicalEvidenceFile(text(resultPath));
    if (returned.isEmpty()) return null;
    boolean createNew = role == CodeToolRole.CREATE
        && "create_new".equals(text(data.get("conflict_resolution")))
        && Boolean.TRUE.equals(data.get("created"));
    if (createNew) {
      String originalRaw = text(arguments.get("__requested_path"));
      boolean transformedInvocation = !originalRaw.isEmpty();
      if (!transformedInvocation) originalRaw = text(arguments.get("path"));
      String invocationRequested = canonicalPath(originalRaw);
      String resultRequested = canonicalPath(text(data.get("requested_path")));
      if (invocationRequested.isEmpty()
          || resultRequested.isEmpty()
          || !invocationRequested.equals(resultRequested)
          || invocationRequested.equals(returned)
          || (transformedInvocation && !invocationPath.equals(returned))) return null;
      PlanFile target = uniqueCreatePlanFile(invocationRequested);
      if (target == null) return null;
      target.canonicalPath = returned;
      createdFileBindings.put(target.id, returned);
      return new ResolvedWrite(returned, target.id);
    }
    return invocationPath.equals(returned) ? new ResolvedWrite(returned, "") : null;
  }

  private void collectReadPaths(Map<String, Object> data, Set<String> output) {
    if (data == null) return;
    for (String key : new String[] {"resolved_path", "path"}) addPath(data.get(key), output);
    for (String key : new String[] {"read_paths", "paths"}) {
      Object raw = data.get(key);
      if (raw instanceof List) for (Object path : (List<?>) raw) addPath(path, output);
    }
    Object items = data.get("items");
    if (!(items instanceof List)) return;
    for (Object raw : (List<?>) items) {
      if (!(raw instanceof Map)) continue;
      Object nested = ((Map<?, ?>) raw).get("result");
      if (!(nested instanceof Map)) continue;
      Map<?, ?> item = (Map<?, ?>) nested;
      if (!item.containsKey("content")
          && !item.containsKey("path")
          && !item.containsKey("resolved_path")) continue;
      addPath(firstValue(item, "resolved_path", "path"), output);
    }
  }

  private void addPath(Object value, Set<String> output) {
    String resolved = canonicalEvidenceFile(text(value));
    if (!resolved.isEmpty()) output.add(resolved);
  }

  private String canonicalEvidenceFile(String raw) {
    String canonical = canonicalPath(raw);
    if (canonical.isEmpty()) return "";
    try {
      File resolved = workspace != null ? workspace.resolveSafely(raw) : new File(raw).getCanonicalFile();
      return resolved.exists() && resolved.isFile() ? canonical : "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private String canonicalPath(String raw) {
    return canonicalPath(raw, false);
  }

  private String canonicalPath(String raw, boolean allowDirectory) {
    if (raw.isEmpty()) return "";
    try {
      File resolved = workspace != null ? workspace.resolveSafely(raw) : new File(raw).getCanonicalFile();
      if (!allowDirectory && resolved.exists() && resolved.isDirectory()) return "";
      return resolved.getCanonicalPath();
    } catch (Exception ignored) {
      return "";
    }
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
    if ("discover".equals(step.phase)) return hasRole(CodeToolRole.READ) || hasRole(CodeToolRole.DISCOVER);
    if ("implement".equals(step.phase)) return implementationSatisfied(step);
    if ("verify".equals(step.phase)) {
      for (String tool : requiredVerificationTools(step)) if (!hasCurrentTool(tool)) return false;
      return !requiredVerificationTools(step).isEmpty();
    }
    if ("quality".equals(step.phase)) {
      return hasRoleAtRevision(CodeToolRole.QUALITY);
    }
    return false;
  }

  private boolean implementationSatisfied(PlanStep step) {
    if (!step.fileRefs.isEmpty()) {
      for (String ref : step.fileRefs) {
        PlanFile file = file(ref);
        if (file == null || !hasWriteFor(file)) return false;
      }
      return true;
    }
    Set<CodeToolRole> accepted = implementationRoles(step.requiredTools);
    for (Evidence item : evidence) {
      if (item.generation == generation && accepted.contains(item.role) && item.passed) return true;
    }
    return false;
  }

  private boolean hasWriteFor(PlanFile file) {
    if (file.canonicalPath.isEmpty() || unresolvedWritePaths.contains(file.canonicalPath)) return false;
    for (Evidence item : evidence) {
      if (item.generation != generation || !item.passed || !file.canonicalPath.equals(item.path)) continue;
      if (!item.planFileId.isEmpty() && !item.planFileId.equals(file.id)) continue;
      if ("create".equals(file.action) && item.role == CodeToolRole.CREATE) return true;
      if ("edit".equals(file.action) && item.role == CodeToolRole.EDIT) return true;
      if (file.action.isEmpty()
          && (item.role == CodeToolRole.CREATE || item.role == CodeToolRole.EDIT)) return true;
    }
    return false;
  }

  private Set<CodeToolRole> implementationRoles(List<String> toolNames) {
    Set<CodeToolRole> out = new LinkedHashSet<>();
    for (String tool : toolNames) {
      CodeToolRole candidate = role(canonicalToolName(tool));
      if (candidate == CodeToolRole.CREATE || candidate == CodeToolRole.EDIT) out.add(candidate);
    }
    if (out.isEmpty()) {
      out.add(CodeToolRole.CREATE);
      out.add(CodeToolRole.EDIT);
    }
    return out;
  }

  private List<String> requiredVerificationTools(PlanStep step) {
    List<String> required = new ArrayList<>(contract.requiredEvidence("code_generation"));
    if (!required.isEmpty()) return required;
    for (String tool : step.requiredTools) {
      String canonical = canonicalToolName(tool);
      if (role(canonical) == CodeToolRole.VERIFY && !required.contains(canonical)) required.add(canonical);
    }
    return required;
  }

  private boolean hasCurrentTool(String toolName) {
    String expected = canonicalToolName(toolName);
    if ("browser_test".equals(expected) && !requiredInteractionIds().isEmpty()
        && !missingRequiredInteractionIds().isEmpty()) return false;
    for (Evidence item : evidence) {
      if (item.generation == generation
          && item.revision == revision
          && item.passed
          && expected.equals(item.toolName)) return true;
    }
    return false;
  }

  private boolean hasRole(CodeToolRole expected) {
    for (Evidence item : evidence) {
      if (item.generation == generation && item.passed && item.role == expected) return true;
    }
    return false;
  }

  private boolean hasRoleAtRevision(CodeToolRole expected) {
    for (Evidence item : evidence) {
      if (item.generation == generation
          && item.revision == revision
          && item.passed
          && item.role == expected) return true;
    }
    return false;
  }

  private void recordInteractionAudit(ToolArguments arguments, ToolResult result) {
    Map<String, Object> data = result.data();
    if (!result.isSuccess()
        || !"run_steps".equals(text(data.get("browser_operation")))
        || Boolean.FALSE.equals(data.get("steps_passed"))) return;
    Object raw = data.get("interaction_audit");
    if (!(raw instanceof Map)) {
      Object layout = data.get("layout_audit");
      if (layout instanceof Map) raw = ((Map<?, ?>) layout).get("interaction_audit");
    }
    if (!(raw instanceof Map) || !Boolean.TRUE.equals(((Map<?, ?>) raw).get("passed"))) return;
    Map<?, ?> audit = (Map<?, ?>) raw;
    Set<String> rejected = new LinkedHashSet<>();
    rejected.addAll(stringList(audit.get("preexisting_assertions")));
    rejected.addAll(stringList(audit.get("indeterminate_assertions")));
    rejected.addAll(stringList(audit.get("invalid_check_ids")));
    for (String checkId : stringList(audit.get("covered_check_ids"))) {
      if (!rejected.contains(checkId)) interactionEvidence.put(checkId, revision);
    }
    for (String checkId : interactionEvidence.keySet()) {
      failedInteractionVariants.remove(failureKey(checkId));
    }
    recompute();
  }

  private void recordFailedInvocation(
      String toolName, ToolArguments arguments, ToolResult result) {
    if (result == null || (result.isSuccess() && !Boolean.FALSE.equals(result.data().get("passed")))) {
      return;
    }
    String invocationSignature = invocationSignature(toolName, arguments);
    if (!invocationSignature.isEmpty()) failedInvocationSignatures.add(invocationSignature);
    String checkId = text(result.data().get("failed_check_id"));
    Object failure = result.data().get("step_failure");
    if (checkId.isEmpty() && failure instanceof Map) {
      checkId = text(((Map<?, ?>) failure).get("check_id"));
    }
    if (checkId.isEmpty()) {
      List<String> missing = stringList(result.data().get("missing_check_ids"));
      if (!missing.isEmpty()) checkId = missing.get(0);
    }
    if (checkId.isEmpty()) {
      nextAction = "change_strategy";
    } else {
      String key = failureKey(checkId);
      Set<String> variants = failedInteractionVariants.computeIfAbsent(
          key, ignored -> new LinkedHashSet<>());
      String failureClass = text(result.data().get("failure_class"));
      variants.add((failureClass.isEmpty() ? "unknown" : failureClass)
          + ":" + invocationSignature);
      nextAction = variants.size() >= 2
          ? "replan_unverifiable_check:" + limit(checkId, 48)
          : "repair_interaction_check:" + limit(checkId, 48);
    }
    recompute();
  }

  private String failureKey(String checkId) {
    return revision + ":" + text(checkId);
  }

  private String invocationSignature(String toolName, ToolArguments arguments) {
    if (toolName == null || arguments == null) return "";
    return toolName + ":" + revision + ":" + arguments.asMap().hashCode();
  }

  private List<String> requiredInteractionIds() {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> check : interactionChecks()) {
      if (Boolean.TRUE.equals(check.get("required"))) addUnique(out, text(check.get("check_id")));
    }
    return out;
  }

  private List<String> coveredRequiredInteractionIds() {
    List<String> out = new ArrayList<>();
    for (String id : requiredInteractionIds()) {
      if (interactionEvidence.getOrDefault(id, -1L) == revision) out.add(id);
    }
    return out;
  }

  private List<String> missingRequiredInteractionIds() {
    List<String> covered = coveredRequiredInteractionIds();
    List<String> out = new ArrayList<>();
    for (String id : requiredInteractionIds()) if (!covered.contains(id)) out.add(id);
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> interactionChecks() {
    Object raw = normalizedPlan == null ? null : normalizedPlan.get("interaction_checks");
    if (!(raw instanceof List)) return Collections.emptyList();
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : (List<?>) raw) if (item instanceof Map) {
      out.add((Map<String, Object>) item);
    }
    return out;
  }

  private List<String> advisoryWarnings() {
    List<String> out = new ArrayList<>();
    for (Map<String, Object> check : interactionChecks()) {
      if (Boolean.TRUE.equals(check.get("required"))) continue;
      String description = limit(text(check.get("description")), 72);
      String reason = limit(text(check.get("advisory_reason")), 48);
      addUnique(out, description + (reason.isEmpty() ? "" : "（" + reason + "）"));
    }
    Object waived = normalizedPlan == null ? null : normalizedPlan.get("waived_checks");
    if (waived instanceof List) for (Object item : (List<?>) waived) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> map = (Map<?, ?>) item;
      String id = limit(text(map.get("check_id")), 48);
      String reason = limit(text(map.get("reason")), 64);
      addUnique(out, "已调整检查 " + id + (reason.isEmpty() ? "" : "：" + reason));
    }
    return out;
  }

  private List<String> missingBeforeQuality() {
    List<String> out = new ArrayList<>();
    for (PlanStep step : steps) {
      if ("quality".equals(step.phase) || step.done) continue;
      out.addAll(missing(step));
    }
    return unique(out);
  }

  private boolean requiresQuality() {
    if ("interface_product".equals(qualityMode())) return true;
    for (PlanStep step : steps) if ("quality".equals(step.phase)) return true;
    return false;
  }

  private String nextActionFor(List<String> missingChecks, List<String> evidenceMissing) {
    if (!missingChecks.isEmpty()) return "run_interaction_check:" + limit(missingChecks.get(0), 48);
    if (evidenceMissing != null && !evidenceMissing.isEmpty()) return limit(evidenceMissing.get(0), 72);
    if (requiresQuality() && !hasRoleAtRevision(CodeToolRole.QUALITY)) return "quality_review";
    return "";
  }

  private void mergeAdvisoryWarnings(Map<String, Object> data) {
    List<String> warnings = advisoryWarnings();
    if (warnings.isEmpty()) return;
    List<String> issues = stringList(data.get("minor_issues"));
    for (String warning : warnings) addUnique(issues, warning);
    data.put("minor_issues", issues);
    data.put("advisory_warnings", warnings);
  }

  private void appendWaivedChecks(Map<String, Object> previous, Map<String, Object> next) {
    if (previous == null) return;
    Map<String, Map<String, Object>> nextRequired = requiredCheckMap(next);
    Map<String, Map<String, Object>> nextAll = checkMap(next);
    List<Map<String, Object>> waived = new ArrayList<>();
    Object existing = previous.get("waived_checks");
    if (existing instanceof List) for (Object item : (List<?>) existing) {
      if (item instanceof Map) waived.add(new LinkedHashMap<>((Map<String, Object>) item));
    }
    for (Map.Entry<String, Map<String, Object>> entry : requiredCheckMap(previous).entrySet()) {
      if (nextRequired.containsKey(entry.getKey())) continue;
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("check_id", entry.getKey());
      value.put("description", entry.getValue().get("description"));
      Map<String, Object> downgraded = nextAll.get(entry.getKey());
      String reason = downgraded == null ? text(next.get("replan_reason"))
          : text(downgraded.get("advisory_reason"));
      value.put("reason", reason.isEmpty() ? "replan_removed_required_check" : reason);
      waived.add(value);
    }
    if (!waived.isEmpty()) next.put("waived_checks", waived);
  }

  private static Map<String, Map<String, Object>> requiredCheckMap(Map<String, Object> plan) {
    Map<String, Map<String, Object>> all = checkMap(plan);
    all.entrySet().removeIf(entry -> !Boolean.TRUE.equals(entry.getValue().get("required")));
    return all;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> checkMap(Map<String, Object> plan) {
    Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    if (plan == null || !(plan.get("interaction_checks") instanceof List)) return out;
    for (Object item : (List<?>) plan.get("interaction_checks")) if (item instanceof Map) {
      Map<String, Object> check = (Map<String, Object>) item;
      String id = text(check.get("check_id"));
      if (!id.isEmpty()) out.put(id, check);
    }
    return out;
  }

  private static String joinMissing(List<String> checks, List<String> evidence) {
    List<String> all = new ArrayList<>();
    if (checks != null) for (String check : checks) all.add("interaction:" + check);
    if (evidence != null) all.addAll(evidence);
    return all.isEmpty() ? "unknown" : String.join(", ", unique(all));
  }

  private static List<String> unique(List<String> source) {
    return new ArrayList<>(new LinkedHashSet<>(source == null ? Collections.emptyList() : source));
  }

  private static void addUnique(List<String> target, String value) {
    if (value != null && !value.isEmpty() && !target.contains(value)) target.add(value);
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
    List<String> required = requiredInteractionIds();
    List<String> covered = coveredRequiredInteractionIds();
    List<String> missingChecks = missingRequiredInteractionIds();
    state.put("ready_for_quality", missingBeforeQuality().isEmpty() && missingChecks.isEmpty());
    state.put("completion_ready", isComplete());
    if (!required.isEmpty()) state.put("required_check_ids", required);
    if (!covered.isEmpty()) state.put("covered_check_ids", covered);
    if (!missingChecks.isEmpty()) state.put("missing_check_ids", missingChecks);
    List<String> advisory = advisoryWarnings();
    if (!advisory.isEmpty()) state.put("advisory_warnings", advisory);
    String action = !nextAction.isEmpty() ? nextAction : nextActionFor(missingChecks, missingBeforeQuality());
    if (!action.isEmpty()) state.put("next_action", action);
    state.put("done_steps", done);
    trimState(state);
    return state;
  }

  private List<String> missing(PlanStep step) {
    List<String> out = new ArrayList<>();
    if ("discover".equals(step.phase)) {
      out.add("read:actual_content");
    } else if ("implement".equals(step.phase)) {
      if (step.fileRefs.isEmpty()) {
        out.add("write:planned_target");
      } else {
        for (String ref : step.fileRefs) {
          PlanFile file = file(ref);
          if (file != null && !hasWriteFor(file)) {
            String role = "create".equals(file.action) ? "create" : "edit";
            out.add(evidenceLabel(role, file.displayPath));
          }
        }
      }
    } else if ("verify".equals(step.phase)) {
      for (String tool : requiredVerificationTools(step)) {
        if (!hasCurrentTool(tool)) out.add(limit("verify:" + tool, 88));
      }
    } else if ("quality".equals(step.phase) && !hasRoleAtRevision(CodeToolRole.QUALITY)) {
      out.add("quality:quality_review");
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static void trimState(Map<String, Object> state) {
    Object raw = state.get("missing_evidence");
    List<String> missing = raw instanceof List ? (List<String>) raw : new ArrayList<>();
    Object advisoryRaw = state.get("advisory_warnings");
    List<String> advisory = advisoryRaw instanceof List
        ? (List<String>) advisoryRaw : new ArrayList<>();
    while (jsonLength(state) > MAX_STATE_CHARS && advisory.size() > 1) {
      advisory.remove(advisory.size() - 1);
    }
    while (jsonLength(state) > MAX_STATE_CHARS && missing.size() > 1) {
      missing.remove(missing.size() - 1);
    }
    if (jsonLength(state) > MAX_STATE_CHARS) state.remove("advisory_warnings");
    if (jsonLength(state) > MAX_STATE_CHARS) state.remove("covered_check_ids");
    if (jsonLength(state) > MAX_STATE_CHARS && !missing.isEmpty()) {
      missing.set(0, limit(missing.get(0), 32));
    }
  }

  private static int jsonLength(Object value) {
    if (value == null) return 4;
    if (value instanceof Map) {
      int size = 2;
      boolean first = true;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (!first) size++;
        first = false;
        size += jsonStringLength(String.valueOf(entry.getKey())) + 1 + jsonLength(entry.getValue());
      }
      return size;
    }
    if (value instanceof Collection) {
      int size = 2;
      boolean first = true;
      for (Object item : (Collection<?>) value) {
        if (!first) size++;
        first = false;
        size += jsonLength(item);
      }
      return size;
    }
    if (value instanceof Number || value instanceof Boolean) return String.valueOf(value).length();
    return jsonStringLength(String.valueOf(value));
  }

  private static int jsonStringLength(String value) {
    int size = 2;
    for (int index = 0; index < value.length(); index++) {
      char item = value.charAt(index);
      size += item == '"' || item == '\\' || item < 0x20 ? 2 : 1;
    }
    return size;
  }

  private Map<String, Object> state() {
    return new ToolArguments(currentState).asMap();
  }

  private CodeToolRole role(String toolName) {
    return roles.get(toolName);
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

  private List<PlanFile> decodeFiles(Object raw) {
    List<PlanFile> out = new ArrayList<>();
    if (!(raw instanceof List)) return out;
    for (Object item : (List<?>) raw) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> map = (Map<?, ?>) item;
      String path = text(map.get("path"));
      String id = text(map.get("file_id"));
      if (!path.isEmpty() && !id.isEmpty()) {
        String canonical = canonicalPath(path);
        if (createdFileBindings.containsKey(id)) canonical = createdFileBindings.get(id);
        out.add(new PlanFile(id, path, canonical, text(map.get("action"))));
      }
    }
    return out;
  }

  private static List<PlanStep> decodeSteps(Object raw) {
    List<PlanStep> out = new ArrayList<>();
    if (!(raw instanceof List)) return out;
    for (Object item : (List<?>) raw) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> map = (Map<?, ?>) item;
      out.add(new PlanStep(
          text(map.get("id")), text(map.get("title")), text(map.get("phase")),
          stringList(map.get("required_tools")), stringList(map.get("file_refs"))));
    }
    return out;
  }

  private PlanFile file(String id) {
    for (PlanFile file : files) if (file.id.equals(id)) return file;
    return null;
  }

  private PlanFile uniqueCreatePlanFile(String canonicalRequested) {
    PlanFile match = null;
    for (PlanFile file : files) {
      if ((!"create".equals(file.action) && !file.action.isEmpty())
          || !canonicalRequested.equals(file.canonicalPath)) continue;
      if (match != null) return null;
      match = file;
    }
    return match;
  }

  private static List<String> stringList(Object raw) {
    List<String> out = new ArrayList<>();
    if (raw instanceof List) for (Object value : (List<?>) raw) {
      String item = text(value);
      if (!item.isEmpty()) out.add(item);
    }
    return out;
  }

  private static Object firstValue(Map<?, ?> map, String... keys) {
    if (map == null) return null;
    for (String key : keys) if (map.containsKey(key)) return map.get(key);
    return null;
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

  private static boolean writeMutated(Map<String, Object> data) {
    return Boolean.TRUE.equals(data.get("changed"))
        || Boolean.TRUE.equals(data.get("current_file_changed"))
        || Boolean.TRUE.equals(data.get("created"))
        || Boolean.TRUE.equals(data.get("overwritten"))
        || Boolean.TRUE.equals(data.get("partial_apply"))
        || number(data.get("applied_count")) > 0;
  }

  private static boolean hasPartialWrite(Map<String, Object> data) {
    if (Boolean.TRUE.equals(data.get("partial_apply"))
        || number(data.get("failed_count")) > 0
        || number(data.get("skipped_count")) > 0
        || nonEmptyValue(data.get("failed_indexes"))
        || nonEmptyValue(data.get("skipped_indexes"))
        || nonEmptyValue(data.get("failed_replacements"))
        || nonEmptyValue(data.get("skipped_replacements"))
        || nonEmptyValue(data.get("failed_units"))
        || nonEmptyValue(data.get("skipped_units"))
        || nonEmptyValue(data.get("failures"))
        || nonEmptyValue(data.get("errors"))
        || containsFailedResult(data.get("results"))) return true;
    long requested = number(data.get("requested_count"));
    if (requested <= 0) return false;
    long applied = number(data.get("applied_count"));
    long unchanged = Math.max(number(data.get("no_change_count")), number(data.get("unchanged_count")));
    return requested > applied + unchanged;
  }

  private static boolean nonEmptyValue(Object value) {
    if (value instanceof Collection) return !((Collection<?>) value).isEmpty();
    if (value instanceof Map) return !((Map<?, ?>) value).isEmpty();
    return value != null && !text(value).isEmpty();
  }

  private static boolean containsFailedResult(Object value) {
    if (!(value instanceof Collection)) return false;
    for (Object item : (Collection<?>) value) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> result = (Map<?, ?>) item;
      if (Boolean.FALSE.equals(result.get("passed"))
          || Boolean.FALSE.equals(result.get("success"))
          || "error".equalsIgnoreCase(text(result.get("status")))
          || "failed".equalsIgnoreCase(text(result.get("status")))
          || !text(result.get("error")).isEmpty()) return true;
    }
    return false;
  }

  private static long number(Object value) {
    if (value instanceof Number) return ((Number) value).longValue();
    try {
      return Long.parseLong(text(value));
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String limit(String value, int maxLength) {
    String safe = text(value);
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
  }

  private static String evidenceLabel(String role, String path) {
    String prefix = text(role) + ":";
    String value = text(path);
    int max = 56;
    if (prefix.length() + value.length() <= max) return prefix + value;
    int tail = Math.max(8, max - prefix.length() - 1);
    return prefix + "…" + value.substring(Math.max(0, value.length() - tail));
  }

  private static final class Evidence {
    final String toolName;
    final CodeToolRole role;
    final String path;
    final String planFileId;
    final long generation;
    final long revision;
    final boolean passed;

    Evidence(
        String toolName,
        CodeToolRole role,
        String path,
        String planFileId,
        long generation,
        long revision,
        boolean passed) {
      this.toolName = toolName;
      this.role = role;
      this.path = path;
      this.planFileId = planFileId;
      this.generation = generation;
      this.revision = revision;
      this.passed = passed;
    }
  }

  private static final class PlanFile {
    final String id;
    final String displayPath;
    String canonicalPath;
    final String action;

    PlanFile(String id, String displayPath, String canonicalPath, String action) {
      this.id = id;
      this.displayPath = displayPath;
      this.canonicalPath = canonicalPath;
      this.action = action;
    }
  }

  private static final class ResolvedWrite {
    final String path;
    final String planFileId;

    ResolvedWrite(String path, String planFileId) {
      this.path = path;
      this.planFileId = planFileId;
    }
  }

  private static final class PlanStep {
    final String id;
    final String title;
    final String phase;
    final List<String> requiredTools;
    final List<String> fileRefs;
    boolean done;

    PlanStep(
        String id,
        String title,
        String phase,
        List<String> requiredTools,
        List<String> fileRefs) {
      this.id = id;
      this.title = title;
      this.phase = phase;
      this.requiredTools = requiredTools;
      this.fileRefs = fileRefs;
    }
  }
}
