package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.*;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.*;

/** Run-scoped managed plan shared by Code Agent tools and completion validation. */
final class ManagedCodePlanCoordinator implements ToolPolicy, AgentRunLifecycle {
  private static final int MAX_STATE_CHARS = 800;
  private final CodePlanningMode mode;
  private final CodeValidationContract contract;
  private final Map<String, CodeToolRole> roles;
  private final WorkspaceAccess workspace;
  private final CodePlanNormalizer normalizer;
  private final boolean editVisibleDuringVerify;
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
  private final Map<String, ReadCoverage> readCoverageByPath = new LinkedHashMap<>();
  private final Map<String, String> createdFileBindings = new LinkedHashMap<>();
  private final Set<String> unresolvedWritePaths = new LinkedHashSet<>();
  private Map<String, Object> currentState = Collections.emptyMap();
  private boolean stateChanged;
  private String verificationFailureTool = "";
  private String verificationFailureKind = "";
  private String recoverableEditPath = "";
  private boolean recoveryReadReady;
  private boolean interactionRequired;
  private List<String> requiredInteractionCheckIds = new ArrayList<>();
  private List<String> browserScenarioIds = new ArrayList<>();
  private String browserPlanId = "";
  private long browserRevision = -1L;
  private String browserSourceRevision = "";
  private String browserTestPlanHash = "";
  private String pendingBrowserRegressionPlanId = "";
  private String pendingBrowserRegressionPlanHash = "";
  private List<Map<String, Object>> browserVerifiedBehaviorEvidence = new ArrayList<>();

  ManagedCodePlanCoordinator(
      CodePlanningMode mode,
      CodeValidationContract contract,
      Map<String, CodeToolRole> roles) {
    this(mode, contract, roles, null, true);
  }

  ManagedCodePlanCoordinator(
      CodePlanningMode mode,
      CodeValidationContract contract,
      Map<String, CodeToolRole> roles,
      WorkspaceAccess workspace) {
    this(mode, contract, roles, workspace, true);
  }

  ManagedCodePlanCoordinator(
      CodePlanningMode mode,
      CodeValidationContract contract,
      Map<String, CodeToolRole> roles,
      WorkspaceAccess workspace,
      boolean editVisibleDuringVerify) {
    this.mode = mode == null ? CodePlanningMode.ADAPTIVE : mode;
    this.contract = contract;
    this.roles = new LinkedHashMap<>(roles);
    this.workspace = workspace;
    this.editVisibleDuringVerify = editVisibleDuringVerify;
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
    readCoverageByPath.clear();
    createdFileBindings.clear();
    unresolvedWritePaths.clear();
    currentState = Collections.emptyMap();
    stateChanged = false;
    verificationFailureTool = "";
    verificationFailureKind = "";
    recoverableEditPath = "";
    recoveryReadReady = false;
    clearInteractionContract();
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
    readCoverageByPath.clear();
    createdFileBindings.clear();
    unresolvedWritePaths.clear();
    currentState = Collections.emptyMap();
    stateChanged = false;
    verificationFailureTool = "";
    verificationFailureKind = "";
    recoverableEditPath = "";
    recoveryReadReady = false;
    clearInteractionContract();
  }

  @Override
  public synchronized ToolSelection selectTools(
      AgentRoundContext context, List<ToolSpec> registeredTools) {
    LinkedHashSet<String> allowed = new LinkedHashSet<>();
    allowed.add(CodeAgentToolNames.FINALIZE_TASK);
    // Reads are non-destructive. Keep the two model-facing read tools available in every
    // coding round and let the model choose between a full baseline read and a focused plan.
    allowed.add("read_file");
    allowed.add("read_plan");
    if (mode != CodePlanningMode.SKIP) allowed.add(CodeAgentToolNames.PLAN_TASK);

    if (!hasPlan()) {
      allowed.add("list_dir");
      if (mode == CodePlanningMode.FORCE) {
        return ToolSelection.onlyNames(registeredTools, allowed);
      }
      if (hasRoleAtRevision(CodeToolRole.CREATE) || hasRoleAtRevision(CodeToolRole.EDIT)) {
        keepSearchReplaceReachable(allowed);
        String missingVerification = nextMissingContractVerification("code_generation");
        if (!missingVerification.isEmpty()) allowed.add(missingVerification);
        else if (qualityEvidenceRequired("code_generation")
            && !hasRoleAtRevision(CodeToolRole.QUALITY)) {
          allowed.add(CodeAgentToolNames.QUALITY_REVIEW);
        }
      } else if (hasReadyReadCoverage()) {
        addRoles(allowed, CodeToolRole.CREATE, CodeToolRole.EDIT);
      }
      return ToolSelection.onlyNames(registeredTools, allowed);
    }

    if (!recoverableEditPath.isEmpty()) {
      if (editVisibleDuringVerify) {
        addSearchReplace(allowed);
      } else if (recoveryReadReady || hasReadyReadCoverage()) {
        addRoles(allowed, CodeToolRole.CREATE, CodeToolRole.EDIT);
      }
      return ToolSelection.onlyNames(registeredTools, allowed);
    }

    if (!verificationFailureTool.isEmpty()) {
      if (retryBrowserPlanWithoutCodeRead()) {
        allowed.add("browser_test");
      } else if (hasRoleAtRevision(CodeToolRole.CREATE)
          || hasRoleAtRevision(CodeToolRole.EDIT)
          || hasReadyReadCoverage()) {
        if (editVisibleDuringVerify) addSearchReplace(allowed);
        else addRoles(allowed, CodeToolRole.CREATE, CodeToolRole.EDIT);
      }
      return ToolSelection.onlyNames(registeredTools, allowed);
    }

    // Completion evidence is authoritative even when an advisory plan step did not map cleanly
    // to the tool history. Once all real verification evidence exists, keep quality reachable.
    if (verificationEvidenceReady("code_generation")
        && qualityEvidenceRequired("code_generation")
        && !hasCurrentTool(CodeAgentToolNames.QUALITY_REVIEW)) {
      allowed.add(CodeAgentToolNames.QUALITY_REVIEW);
      keepSearchReplaceReachable(allowed);
      return ToolSelection.onlyNames(registeredTools, allowed);
    }

    if (hasRoleAtRevision(CodeToolRole.CREATE) || hasRoleAtRevision(CodeToolRole.EDIT)) {
      keepSearchReplaceReachable(allowed);
      String missingVerification = nextMissingVerificationTool();
      if (!missingVerification.isEmpty()) allowed.add(missingVerification);
      else if (qualityRequired()) allowed.add(CodeAgentToolNames.QUALITY_REVIEW);
      return ToolSelection.onlyNames(registeredTools, allowed);
    }

    PlanStep current = currentStep();
    if (current != null) {
      List<String> declaredVerification = declaredVerificationTools(current);
      if (!declaredVerification.isEmpty()) {
        String missingVerification = nextMissingVerificationTool();
        if (!missingVerification.isEmpty()) allowed.add(missingVerification);
        keepSearchReplaceReachable(allowed);
      } else if ("discover".equals(current.phase)) {
        allowed.add("list_dir");
      } else if ("implement".equals(current.phase)) {
        if (!requiresReadCoverage(current) || hasReadyReadCoverage()) {
          addRoles(allowed, CodeToolRole.CREATE, CodeToolRole.EDIT);
        }
      } else if ("verify".equals(current.phase)) {
        String missingVerification = nextMissingVerificationTool();
        if (!missingVerification.isEmpty()) allowed.add(missingVerification);
        keepSearchReplaceReachable(allowed);
      } else if ("quality".equals(current.phase)) {
        allowed.add(CodeAgentToolNames.QUALITY_REVIEW);
        if (editVisibleDuringVerify) {
          keepSearchReplaceReachable(allowed);
        } else if (hasReadyReadCoverage()) {
          addRoles(allowed, CodeToolRole.CREATE, CodeToolRole.EDIT);
        }
      }
      return ToolSelection.onlyNames(registeredTools, allowed);
    }

    if (hasReadyReadCoverage()) addRoles(allowed, CodeToolRole.CREATE, CodeToolRole.EDIT);
    else allowed.add("list_dir");
    return ToolSelection.onlyNames(registeredTools, allowed);
  }

  @Override
  public synchronized boolean supports(ToolInvocation invocation) {
    if (invocation == null) return false;
    String toolName = invocation.tool().spec().name();
    CodeToolRole role = role(toolName);
    if (CodeAgentToolNames.FINALIZE_TASK.equals(toolName)) {
      return "completed".equals(text(invocation.arguments().get("status")));
    }
    return mode == CodePlanningMode.FORCE
        && !hasPlan()
        && (role == CodeToolRole.CREATE || role == CodeToolRole.EDIT);
  }

  @Override
  public synchronized Cancellable evaluate(
      ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
    String toolName = invocation.tool().spec().name();
    if (CodeAgentToolNames.FINALIZE_TASK.equals(toolName)
        && "completed".equals(text(invocation.arguments().get("status")))) {
      String completionType = text(invocation.arguments().get("completion_type"));
      if (completionType.isEmpty()) completionType = "code_generation";
      if (!completionEvidenceReady(completionType)) {
        String missingStage = nextCompletionAction(completionType);
        callback.resolve(ToolPolicyDecision.error(
            "finalize_precondition_failed",
            "完成证据尚未齐全，请先执行当前缺失阶段。",
            true,
            preconditionData("finalize_task", missingStage)));
      } else {
        callback.resolve(ToolPolicyDecision.proceed(invocation.arguments()));
      }
      return Cancellable.NONE;
    }
    callback.resolve(ToolPolicyDecision.error(
        "managed_plan_required",
        "当前任务要求在首次写入前调用 plan_task 建立短计划。",
        true));
    return Cancellable.NONE;
  }

  synchronized long generation() {
    return generation;
  }

  synchronized ToolResult preflightResult(String toolName, ToolArguments arguments) {
    if ("browser_test".equals(toolName) && hasPlan()) {
      BrowserTestContractValidator.Report report = BrowserTestContractValidator.validate(
          arguments == null ? Collections.<String, Object>emptyMap() : arguments.asMap(),
          requiredInteractionCheckIds,
          interactionRequired);
      if (!report.valid()) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "browser_test");
        data.put("passed", false);
        data.put("failure_kind", "test_plan_invalid");
        data.put("validation_issues", report.validationIssues());
        data.put("webview_launch_count", 0);
        data.put("recommended_next_action", "browser_test");
        data.put("failure_reason", report.firstMessage());
        Map<String, Object> retryBrief = new LinkedHashMap<>();
        retryBrief.put("goal", "");
        retryBrief.put("issue", report.firstMessage());
        retryBrief.put("recommended_tool", "browser_test");
        retryBrief.put("instruction",
            "一次修正 validation_issues 中的全部测试计划问题并重提 browser_test；"
                + "不要因此读取、replan 或修改产品代码。");
        data.put("test_retry_brief", retryBrief);
        data.put("plan_state", state());
        return ToolResult.success(data);
      }
    }
    if (CodeAgentToolNames.QUALITY_REVIEW.equals(toolName)
        && !verificationEvidenceReady("code_generation")) {
      String missingVerification = "";
      for (String required : completionVerificationTools("code_generation")) {
        if (required.equals(verificationFailureTool) || !hasCurrentTool(required)) {
          missingVerification = required;
          break;
        }
      }
      if (missingVerification.isEmpty()) missingVerification = "syntax_check";
      Map<String, Object> data = new LinkedHashMap<>(arguments.asMap());
      data.put("operation", CodeAgentToolNames.QUALITY_REVIEW);
      data.put("passed", false);
      data.put("minimal_version_risk", false);
      data.put("blocking_gaps", Collections.singletonList(
          "当前 revision 尚未完成 " + missingVerification));
      data.put("recommended_next_action", missingVerification);
      data.put("plan_state", state());
      return ToolResult.success(data);
    }
    return null;
  }

  synchronized Map<String, Object> acceptPlan(Map<String, ?> arguments) {
    Map<String, Object> next = normalizer.normalize(arguments);
    if (normalizedPlan != null && text(next.get("replan_reason")).isEmpty()) {
      next.put("replan_reason", "基于当前任务的新证据调整剩余步骤");
    }
    normalizedPlan = next;
    planId = "plan-" + activeRunId + "-" + (++planSequence);
    evidence.removeIf(item ->
        item.role == CodeToolRole.QUALITY || "browser_test".equals(item.toolName));
    interactionRequired = Boolean.TRUE.equals(next.get("interaction_required"));
    requiredInteractionCheckIds = interactionCheckIds(next.get("interaction_checks"));
    clearPendingBrowserRegression();
    clearBrowserInteractionEvidence();
    verificationFailureTool = "";
    verificationFailureKind = "";
    files = decodeFiles(next.get("planned_files"));
    steps = decodeSteps(next.get("steps"));
    recompute();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operation", CodeAgentToolNames.PLAN_TASK);
    result.put("normalized_plan_version", CodePlanNormalizer.VERSION);
    result.put("normalized_plan", new ToolArguments(next).asMap());
    result.put("plan_state", state());
    result.put("recommended_next_action", nextManagedAction());
    stateChanged = false;
    return result;
  }

  @SuppressWarnings("unchecked")
  synchronized ToolResult recordAndDecorate(
      long capturedGeneration,
      String toolName,
      ToolArguments arguments,
      ToolResult result) {
    if (capturedGeneration != generation || result == null) return result;
    ToolResult effective = normalizeManagedResult(
        toolName, arguments == null ? ToolArguments.empty() : arguments, result);
    if (!CodeAgentToolNames.PLAN_TASK.equals(toolName)) {
      record(toolName, arguments == null ? ToolArguments.empty() : arguments, effective);
    }
    boolean contractResult = hasPlan()
        && ("browser_test".equals(toolName)
            || CodeAgentToolNames.QUALITY_REVIEW.equals(toolName)
            || CodeAgentToolNames.FINALIZE_TASK.equals(toolName));
    boolean recoverableRetry = hasPlan()
        && editVisibleDuringVerify
        && recoverableSearchReplaceFailure(toolName, effective);
    if ((!stateChanged && !contractResult && !recoverableRetry) || !hasPlan()) return effective;
    Map<String, Object> data = new LinkedHashMap<>(effective.data());
    if (stateChanged || contractResult) data.put("plan_state", state());
    if (contractResult) decorateManagedResult(toolName, data);
    if ("browser_test".equals(toolName) && Boolean.TRUE.equals(data.get("passed"))) {
      data.put("recommended_next_action", recommendedNextAction());
    } else if (text(data.get("recommended_next_action")).isEmpty()) {
      data.put("recommended_next_action", recommendedNextAction());
    }
    stateChanged = false;
    if (effective.isSuccess()) return ToolResult.success(data);
    if (effective.status() == ToolResult.Status.ERROR) {
      return ToolResult.error(
          effective.errorCode(), effective.message(), effective.retryable(), data);
    }
    return effective;
  }

  /** Kept package-compatible for tests and integrations compiled against the V2 internals. */
  synchronized ToolResult recordAndDecorate(
      long capturedGeneration, String toolName, ToolResult result) {
    return recordAndDecorate(capturedGeneration, toolName, ToolArguments.empty(), result);
  }

  @SuppressWarnings("unchecked")
  private ToolResult normalizeManagedResult(
      String toolName, ToolArguments arguments, ToolResult result) {
    if (!result.isSuccess()) return result;
    Map<String, Object> data = new LinkedHashMap<>(result.data());
    if ("browser_test".equals(toolName) && Boolean.TRUE.equals(data.get("passed"))) {
      String currentHash = text(data.get("test_plan_hash"));
      if (!pendingBrowserRegressionPlanHash.isEmpty()
          && pendingBrowserRegressionPlanId.equals(planId)
          && !pendingBrowserRegressionPlanHash.equals(currentHash)) {
        data.put("passed", false);
        data.put("failure_kind", "test_plan_invalid");
        data.put("failure_reason", "产品修复后的 browser_test 改变了原诊断测试语义");
        data.put("recommended_next_action", "browser_test");
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("path", "browser_test.scenarios");
        issue.put("code", "regression_plan_changed");
        issue.put("message", "请使用产品故障诊断时相同的 actions、wait_for 和 expectations 回归");
        data.put("validation_issues", Collections.singletonList(issue));
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("issue", issue.get("message"));
        retry.put("recommended_tool", "browser_test");
        retry.put("instruction", "保持产品代码不变，恢复原诊断测试语义后重新执行 browser_test");
        data.put("test_retry_brief", retry);
      }
    }
    if ("browser_test".equals(toolName) && Boolean.TRUE.equals(data.get("passed"))) {
      BrowserContract browser = browserContract(arguments);
      List<String> passedIds = passedScenarioResultIds(data.get("scenario_results"));
      boolean structurallyValid = browser.valid(
              requiredInteractionCheckIds.isEmpty(), interactionRequired)
          && !browser.provided.isEmpty()
          && passedIds != null
          && sameIds(browser.provided, passedIds);
      if (structurallyValid) {
        String failureKind = text(data.get("failure_kind"));
        if (failureKind.isEmpty()) data.put("failure_kind", "none");
        else if (!"none".equals(failureKind)) structurallyValid = false;
      }
      if (structurallyValid) {
        Object rawCoverage = data.get("coverage_summary");
        Map<String, Object> coverage = rawCoverage instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) rawCoverage)
            : new LinkedHashMap<>();
        coverage.put("required", browser.provided.size());
        coverage.put("passed", browser.provided.size());
        coverage.put("complete", true);
        coverage.put("passed_scenario_ids", new ArrayList<>(browser.provided));
        coverage.put("failed_scenario_ids", Collections.emptyList());
        data.put("coverage_summary", coverage);
      } else {
        data.put("passed", false);
        data.put("failure_kind", "environment_failure");
        data.put("failure_reason", "browser_test 返回的场景结果不完整或相互矛盾");
        data.put("recommended_next_action", "browser_test");
        Object rawCoverage = data.get("coverage_summary");
        Map<String, Object> coverage = rawCoverage instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) rawCoverage)
            : new LinkedHashMap<>();
        coverage.put("complete", false);
        data.put("coverage_summary", coverage);
      }
    }
    if (CodeAgentToolNames.QUALITY_REVIEW.equals(toolName)) {
      if (!browserVerifiedBehaviorEvidence.isEmpty()) {
        data.put("verified_behavior_evidence",
            new ArrayList<Map<String, Object>>(browserVerifiedBehaviorEvidence));
        data.put("web_evidence", verifiedBehaviorSummaries(browserVerifiedBehaviorEvidence));
      }
      String missingVerification = "";
      for (String required : completionVerificationTools("code_generation")) {
        if (required.equals(verificationFailureTool) || !hasCurrentTool(required)) {
          missingVerification = required;
          break;
        }
      }
      boolean accepted = missingVerification.isEmpty()
          && Boolean.TRUE.equals(data.get("passed"))
          && !Boolean.TRUE.equals(data.get("minimal_version_risk"))
          && emptyValue(data.get("blocking_gaps"))
          && emptyValue(data.get("claimed_but_unsupported"));
      if (!accepted) {
        data.put("passed", false);
        if (!missingVerification.isEmpty() && emptyValue(data.get("blocking_gaps"))) {
          data.put("blocking_gaps", Collections.singletonList(
              "当前 revision 尚未完成 " + missingVerification));
        }
        if (text(data.get("recommended_next_action")).isEmpty()) {
          data.put("recommended_next_action",
              missingVerification.isEmpty() ? "quality_review" : missingVerification);
        }
      }
    }
    if (CodeAgentToolNames.FINALIZE_TASK.equals(toolName)
        && !browserVerifiedBehaviorEvidence.isEmpty()) {
      data.put("verification", verifiedBehaviorSummaries(browserVerifiedBehaviorEvidence));
    }
    return ToolResult.success(data);
  }

  private static List<String> verifiedBehaviorSummaries(
      List<Map<String, Object>> evidence) {
    List<String> values = new ArrayList<>();
    for (Map<String, Object> proof : evidence) {
      String id = text(proof.get("id"));
      int actions = proof.get("action_trace") instanceof Collection
          ? ((Collection<?>) proof.get("action_trace")).size() : 0;
      int expectations = successfulEvidenceCount(proof.get("actual_state"));
      values.add(id + ": verified trace actions=" + actions
          + ", successful postconditions=" + expectations);
    }
    return values;
  }

  private static int successfulEvidenceCount(Object rawState) {
    if (!(rawState instanceof Map)) return 0;
    int count = 0;
    Map<?, ?> state = (Map<?, ?>) rawState;
    for (String key : Arrays.asList("expectations", "checkpoints")) {
      Object raw = state.get(key);
      if (!(raw instanceof Collection)) continue;
      for (Object item : (Collection<?>) raw) {
        if (item instanceof Map && "success".equals(text(((Map<?, ?>) item).get("status")))) {
          count++;
        }
      }
    }
    return count;
  }

  private static List<String> passedScenarioResultIds(Object rawResults) {
    if (!(rawResults instanceof List)) return null;
    List<String> ids = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Object raw : (List<?>) rawResults) {
      if (!(raw instanceof Map)) return null;
      Map<?, ?> result = (Map<?, ?>) raw;
      String id = text(result.get("id"));
      if (id.isEmpty()) id = text(result.get("scenario_id"));
      if (id.isEmpty() || !seen.add(id) || !Boolean.TRUE.equals(result.get("passed"))) return null;
      ids.add(id);
    }
    return ids;
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
    if (role == CodeToolRole.CREATE || role == CodeToolRole.EDIT) {
      recordWrite(toolName, role, arguments, result);
      return;
    }
    if (role == CodeToolRole.VERIFY || role == CodeToolRole.QUALITY) {
      boolean valid = validEvidence(toolName, role, arguments, result);
      if (role == CodeToolRole.VERIFY) {
        recordVerificationRouting(toolName, result, valid);
      }
      if (!valid) {
        if (role == CodeToolRole.QUALITY || !"browser_test".equals(toolName)) {
          boolean removed = evidence.removeIf(item ->
              item.generation == generation
                  && item.revision == revision
                  && (role == CodeToolRole.QUALITY
                      ? item.role == role
                      : toolName.equals(item.toolName)));
          if (removed) recompute();
        }
        return;
      }
      evidence.removeIf(item ->
          item.generation == generation
              && item.revision == revision
              && (role == CodeToolRole.QUALITY ? item.role == role : toolName.equals(item.toolName)));
    } else if (!validEvidence(toolName, role, arguments, result)) return;
    if (role == CodeToolRole.READ) {
      if ("read_file".equals(toolName) && result.isSuccess()) {
        String path = canonicalEvidenceFile(text(firstValue(result.data(), "resolved_path", "path")));
        if (path.isEmpty()) path = canonicalEvidenceFile(text(arguments.get("path")));
        if (!recoverableEditPath.isEmpty()
            && recoverableEditPath.equals(path)
            && result.data().containsKey("content")) {
          recoveryReadReady = true;
        }
      }
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
      recordReadCoverage(paths, result.data());
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
      if (!mutated) {
        if (recoverableSearchReplaceFailure(toolName, result)) {
          recoverableEditPath = write.path;
          recoveryReadReady = false;
        }
        return;
      }
      advanceRevision(write.path);
      unresolvedWritePaths.add(write.path);
      recompute();
      return;
    }
    advanceRevision(write.path);
    recoverableEditPath = "";
    recoveryReadReady = false;
    verificationFailureTool = "";
    verificationFailureKind = "";
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
    pathRevisions.put(path, revision);
    readCoverageByPath.remove(path);
    verificationFailureTool = "";
    verificationFailureKind = "";
    recoverableEditPath = "";
    recoveryReadReady = false;
    clearBrowserInteractionEvidence();
  }

  private boolean validEvidence(
      String toolName, CodeToolRole role, ToolArguments arguments, ToolResult result) {
    if (role == null || !result.isSuccess()) return false;
    Map<String, Object> data = result.data();
    if (Boolean.FALSE.equals(data.get("passed"))) return false;
    if (role == CodeToolRole.READ) {
      if ("read_plan".equals(toolName)) return validReadPlan(data);
      return hasReadContent(data);
    }
    if (role == CodeToolRole.VERIFY) {
      if ("browser_test".equals(toolName)) {
        return captureBrowserTransaction(arguments, data);
      }
      return Boolean.TRUE.equals(data.get("passed"));
    }
    if (role == CodeToolRole.QUALITY) {
      return verificationEvidenceReady("code_generation")
          && (!requiresBrowserEvidence() || hasCurrentBrowserTransaction())
          && Boolean.TRUE.equals(data.get("passed"))
          && !Boolean.TRUE.equals(data.get("minimal_version_risk"))
          && emptyValue(data.get("blocking_gaps"))
          && emptyValue(data.get("claimed_but_unsupported"));
    }
    return role == CodeToolRole.DISCOVER;
  }

  private static boolean validReadPlan(Map<String, Object> data) {
    Object rawCoverage = data.get("coverage_summary");
    if (!(rawCoverage instanceof Map)
        || !Boolean.TRUE.equals(((Map<?, ?>) rawCoverage).get("ready_for_edit"))) return false;
    if (text(data.get("revision")).isEmpty()) return false;
    Object evidence = data.get("evidence");
    if (!(evidence instanceof List) || ((List<?>) evidence).isEmpty()) return false;
    for (Object raw : (List<?>) evidence) {
      if (raw instanceof Map && ((Map<?, ?>) raw).containsKey("content")) return true;
    }
    return false;
  }

  private void recordReadCoverage(List<String> paths, Map<String, Object> data) {
    if (paths == null || paths.isEmpty()) return;
    LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
    Object rawEvidence = data.get("evidence");
    if (rawEvidence instanceof List) {
      for (Object raw : (List<?>) rawEvidence) {
        if (!(raw instanceof Map)) continue;
        String id = text(((Map<?, ?>) raw).get("evidence_id"));
        if (!id.isEmpty()) evidenceIds.add(id);
      }
    }
    String declaredRevision = text(data.get("revision"));
    for (String path : paths) {
      String sourceRevision = declaredRevision.isEmpty()
          ? currentSourceRevision(path) : declaredRevision;
      readCoverageByPath.put(path, new ReadCoverage(sourceRevision, evidenceIds));
    }
  }

  private void recordVerificationRouting(String toolName, ToolResult result, boolean valid) {
    if (valid) {
      if (toolName.equals(verificationFailureTool)) {
        verificationFailureTool = "";
        verificationFailureKind = "";
      }
      if ("browser_test".equals(toolName)) clearPendingBrowserRegression();
      return;
    }
    verificationFailureTool = toolName;
    String kind = text(result.data().get("failure_kind"));
    if ("browser_test".equals(toolName)
        && !valid
        && Boolean.TRUE.equals(result.data().get("passed"))
        && (kind.isEmpty() || "none".equals(kind))) {
      kind = "environment_failure";
    }
    if (kind.isEmpty()) kind = "product_code_failure";
    verificationFailureKind = kind;
    if ("browser_test".equals(toolName)
        && "product_code_failure".equals(kind)
        && !hasIndependentBrowserTestFailure(result.data())) {
      String hash = text(result.data().get("test_plan_hash"));
      if (!hash.isEmpty()) {
        pendingBrowserRegressionPlanId = planId;
        pendingBrowserRegressionPlanHash = hash;
      }
    }
  }

  private static boolean hasIndependentBrowserTestFailure(Map<String, Object> data) {
    boolean sawFailure = false;
    Object rawResults = data.get("scenario_results");
    if (rawResults instanceof Collection) {
      for (Object rawResult : (Collection<?>) rawResults) {
        if (!(rawResult instanceof Map)) continue;
        Object rawFailures = ((Map<?, ?>) rawResult).get("failures");
        if (!(rawFailures instanceof Collection)) continue;
        for (Object rawFailure : (Collection<?>) rawFailures) {
          if (!(rawFailure instanceof Map)) continue;
          Map<?, ?> failure = (Map<?, ?>) rawFailure;
          sawFailure = true;
          String code = text(failure.get("code"));
          if (code.startsWith("blocked_by_")) continue;
          if ("test_expectation_mismatch".equals(text(failure.get("failure_kind")))) {
            return true;
          }
        }
      }
    }
    return !sawFailure && data.containsKey("test_retry_brief");
  }

  private boolean retryBrowserPlanWithoutCodeRead() {
    if (!"browser_test".equals(verificationFailureTool)) return false;
    return "test_plan_invalid".equals(verificationFailureKind)
        || "test_expectation_mismatch".equals(verificationFailureKind)
        || "environment_failure".equals(verificationFailureKind);
  }

  private boolean hasReadyReadCoverage() {
    Iterator<Map.Entry<String, ReadCoverage>> iterator = readCoverageByPath.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, ReadCoverage> entry = iterator.next();
      String current = currentSourceRevision(entry.getKey());
      if (current.isEmpty() || !current.equalsIgnoreCase(entry.getValue().sourceRevision)) {
        iterator.remove();
      }
    }
    return !readCoverageByPath.isEmpty();
  }

  private PlanStep currentStep() {
    for (PlanStep step : steps) if (!step.done) return step;
    return null;
  }

  private boolean requiresReadCoverage(PlanStep step) {
    if (step == null || step.fileRefs.isEmpty()) return true;
    for (String ref : step.fileRefs) {
      PlanFile file = file(ref);
      if (file == null || !"create".equals(file.action)) return true;
      if (!file.canonicalPath.isEmpty()) {
        try {
          File target = workspace == null ? new File(file.canonicalPath) : workspace.resolveSafely(file.displayPath);
          if (target.exists()) return true;
        } catch (Exception ignored) {
          return true;
        }
      }
    }
    return false;
  }

  private String nextMissingVerificationTool() {
    PlanStep current = currentStep();
    List<String> required = new ArrayList<>();
    if (current != null && !declaredVerificationTools(current).isEmpty()) {
      required.addAll(declaredVerificationTools(current));
    } else if (current != null && "verify".equals(current.phase)) {
      required.addAll(requiredVerificationTools(current));
    } else {
      required.addAll(contract.requiredEvidence("code_generation"));
    }
    for (String tool : required) {
      String canonical = canonicalToolName(tool);
      if (role(canonical) == CodeToolRole.VERIFY
          && (canonical.equals(verificationFailureTool) || !hasCurrentTool(canonical))) {
        return canonical;
      }
    }
    return "";
  }

  private String nextMissingContractVerification(String completionType) {
    for (String tool : contract.requiredEvidence(completionType)) {
      String canonical = canonicalToolName(tool);
      if (role(canonical) == CodeToolRole.VERIFY
          && (canonical.equals(verificationFailureTool) || !hasCurrentTool(canonical))) {
        return canonical;
      }
    }
    return "";
  }

  private List<String> completionVerificationTools(String completionType) {
    LinkedHashSet<String> required = new LinkedHashSet<>();
    if (hasPlan()) {
      for (PlanStep step : steps) {
        List<String> declared = declaredVerificationTools(step);
        if (!declared.isEmpty()) required.addAll(declared);
        else if ("verify".equals(step.phase)) required.addAll(requiredVerificationTools(step));
      }
    }
    if (required.isEmpty()) {
      for (String tool : contract.requiredEvidence(completionType)) {
        String canonical = canonicalToolName(tool);
        if (!canonical.isEmpty()) required.add(canonical);
      }
    }
    return new ArrayList<>(required);
  }

  private boolean verificationEvidenceReady(String completionType) {
    for (String tool : completionVerificationTools(completionType)) {
      if (tool.equals(verificationFailureTool) || !hasCurrentTool(tool)) return false;
    }
    return true;
  }

  private boolean qualityEvidenceRequired(String completionType) {
    if (contract.requiresQualityReview(completionType)
        || "interface_product".equals(qualityMode())) return true;
    for (PlanStep step : steps) if ("quality".equals(step.phase)) return true;
    return false;
  }

  synchronized boolean completionEvidenceReady(String completionType) {
    if (mode == CodePlanningMode.FORCE && !hasPlan()) return false;
    if (qualityEvidenceRequired(completionType)) {
      return verificationEvidenceReady(completionType)
          && hasCurrentTool(CodeAgentToolNames.QUALITY_REVIEW);
    }
    return verificationEvidenceReady(completionType);
  }

  private String nextCompletionAction(String completionType) {
    if (mode == CodePlanningMode.FORCE && !hasPlan()) return CodeAgentToolNames.PLAN_TASK;
    if (!verificationFailureTool.isEmpty()) return verificationFailureTool;
    if (verificationEvidenceReady(completionType)
        && qualityEvidenceRequired(completionType)
        && !hasCurrentTool(CodeAgentToolNames.QUALITY_REVIEW)) {
      return CodeAgentToolNames.QUALITY_REVIEW;
    }
    if (hasPlan()) {
      PlanStep current = currentStep();
      if (current != null && ("discover".equals(current.phase) || "implement".equals(current.phase))) {
        return nextManagedAction();
      }
    } else if (!hasRoleAtRevision(CodeToolRole.CREATE)
        && !hasRoleAtRevision(CodeToolRole.EDIT)) {
      return "read_file";
    }
    for (String tool : completionVerificationTools(completionType)) {
      if (tool.equals(verificationFailureTool) || !hasCurrentTool(tool)) return tool;
    }
    if (qualityEvidenceRequired(completionType)
        && !hasCurrentTool(CodeAgentToolNames.QUALITY_REVIEW)) {
      return CodeAgentToolNames.QUALITY_REVIEW;
    }
    return CodeAgentToolNames.FINALIZE_TASK;
  }

  private String recommendedNextAction() {
    if (editVisibleDuringVerify && !recoverableEditPath.isEmpty()) {
      return "search_replace";
    }
    if (editVisibleDuringVerify
        && !verificationFailureTool.isEmpty()
        && !retryBrowserPlanWithoutCodeRead()
        && (hasRoleAtRevision(CodeToolRole.CREATE)
            || hasRoleAtRevision(CodeToolRole.EDIT)
            || hasReadyReadCoverage())) {
      return "search_replace";
    }
    return nextCompletionAction("code_generation");
  }

  private boolean qualityRequired() {
    PlanStep current = currentStep();
    return current != null && "quality".equals(current.phase);
  }

  private boolean requiresBrowserEvidence() {
    if (!hasPlan()) {
      for (String tool : contract.requiredEvidence("code_generation")) {
        if ("browser_test".equals(canonicalToolName(tool))) return true;
      }
      return false;
    }
    for (PlanStep step : steps) {
      if (declaredVerificationTools(step).contains("browser_test")) return true;
      if ("verify".equals(step.phase)
          && requiredVerificationTools(step).contains("browser_test")) return true;
    }
    return false;
  }

  private String nextManagedAction() {
    PlanStep current = currentStep();
    if (current == null) return CodeAgentToolNames.FINALIZE_TASK;
    if (!declaredVerificationTools(current).isEmpty()) {
      String missing = nextMissingVerificationTool();
      return missing.isEmpty() ? CodeAgentToolNames.QUALITY_REVIEW : missing;
    }
    if ("verify".equals(current.phase)) {
      String missing = nextMissingVerificationTool();
      return missing.isEmpty() ? "browser_test" : missing;
    }
    if ("quality".equals(current.phase)) return CodeAgentToolNames.QUALITY_REVIEW;
    if ("discover".equals(current.phase)) return "read_file";
    if ("implement".equals(current.phase)) {
      if (requiresReadCoverage(current) && !hasReadyReadCoverage()) return "read_file";
      if (!current.requiredTools.isEmpty()) return canonicalToolName(current.requiredTools.get(0));
      return "search_replace";
    }
    if (!current.requiredTools.isEmpty()) return canonicalToolName(current.requiredTools.get(0));
    return CodeAgentToolNames.PLAN_TASK;
  }

  private void addRoles(Set<String> output, CodeToolRole... accepted) {
    Set<CodeToolRole> rolesToAdd = new LinkedHashSet<>(Arrays.asList(accepted));
    for (Map.Entry<String, CodeToolRole> entry : roles.entrySet()) {
      if (rolesToAdd.contains(entry.getValue())) output.add(entry.getKey());
    }
  }

  private void keepSearchReplaceReachable(Set<String> output) {
    if (editVisibleDuringVerify
        && (hasRoleAtRevision(CodeToolRole.CREATE)
            || hasRoleAtRevision(CodeToolRole.EDIT)
            || hasReadyReadCoverage())) {
      addSearchReplace(output);
    }
  }

  private void addSearchReplace(Set<String> output) {
    if (role("search_replace") == CodeToolRole.EDIT) output.add("search_replace");
  }

  private static boolean recoverableSearchReplaceFailure(String toolName, ToolResult result) {
    if (!"search_replace".equals(toolName) || result == null || result.isSuccess()) return false;
    String detail = (text(result.errorCode()) + " " + text(result.message())).toLowerCase(Locale.US);
    return detail.contains("search_match_count")
        || detail.contains("no_match")
        || detail.contains("multiple")
        || detail.contains("overlap")
        || detail.contains("brace")
        || detail.contains("batch conflict")
        || detail.contains("batch_conflict");
  }

  private static boolean boundedRecoveryRead(ToolArguments arguments) {
    if (!text(arguments.get("target_function")).isEmpty()
        || !text(arguments.get("target_class")).isEmpty()
        || !text(arguments.get("target_method")).isEmpty()) return true;
    int start = positiveInt(arguments.get("start_line"));
    int end = positiveInt(arguments.get("end_line"));
    return start > 0 && end >= start && end - start + 1 <= 80;
  }

  private static int positiveInt(Object value) {
    if (value instanceof Number) return ((Number) value).intValue();
    try {
      return Integer.parseInt(text(value));
    } catch (Exception ignored) {
      return -1;
    }
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

  private String currentSourceRevision(String canonicalPath) {
    if (canonicalPath == null || canonicalPath.isEmpty()) return "";
    try {
      File file = new File(canonicalPath).getCanonicalFile();
      if (!file.exists() || !file.isFile()) return "";
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (FileInputStream input = new FileInputStream(file)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read > 0) digest.update(buffer, 0, read);
        }
      }
      StringBuilder out = new StringBuilder();
      for (byte value : digest.digest()) out.append(String.format(Locale.US, "%02x", value));
      return out.toString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static boolean hasReadContent(Map<String, Object> data) {
    if (data.containsKey("content")) return true;
    Object evidence = data.get("evidence");
    if (evidence instanceof List) {
      for (Object raw : (List<?>) evidence) {
        if (raw instanceof Map && ((Map<?, ?>) raw).containsKey("content")) return true;
      }
    }
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
    List<String> declaredVerification = declaredVerificationTools(step);
    if (!declaredVerification.isEmpty()) {
      for (String tool : declaredVerification) if (!hasCurrentTool(tool)) return false;
      return true;
    }
    if ("discover".equals(step.phase)) {
      if (!step.requiredTools.isEmpty()) {
        for (String tool : step.requiredTools) if (hasTool(canonicalToolName(tool))) return true;
        return false;
      }
      return hasRole(CodeToolRole.READ) || hasRole(CodeToolRole.DISCOVER);
    }
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
    List<String> required = declaredVerificationTools(step);
    if (!required.isEmpty()) return required;
    required.addAll(contract.requiredEvidence("code_generation"));
    return required;
  }

  private List<String> declaredVerificationTools(PlanStep step) {
    List<String> required = new ArrayList<>();
    for (String tool : step.requiredTools) {
      String canonical = canonicalToolName(tool);
      if (role(canonical) == CodeToolRole.VERIFY && !required.contains(canonical)) required.add(canonical);
    }
    return required;
  }

  private boolean hasCurrentTool(String toolName) {
    String expected = canonicalToolName(toolName);
    for (Evidence item : evidence) {
      if (item.generation == generation
          && item.revision == revision
          && item.passed
          && expected.equals(item.toolName)) return true;
    }
    return false;
  }

  private boolean hasTool(String toolName) {
    String expected = canonicalToolName(toolName);
    for (Evidence item : evidence) {
      if (item.generation == generation && item.passed && expected.equals(item.toolName)) return true;
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
    if (!requiredInteractionCheckIds.isEmpty()) {
      state.put("required_interaction_check_ids", requiredInteractionCheckIds);
      state.put("interaction_verification_ready", hasCurrentBrowserTransaction());
    }
    trimState(state);
    return state;
  }

  private List<String> missing(PlanStep step) {
    List<String> out = new ArrayList<>();
    List<String> declaredVerification = declaredVerificationTools(step);
    if (!declaredVerification.isEmpty()) {
      for (String tool : declaredVerification) {
        if (!hasCurrentTool(tool)) out.add(limit("verify:" + tool, 88));
      }
    } else if ("discover".equals(step.phase)) {
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
    if (!(raw instanceof List)) return;
    List<String> missing = (List<String>) raw;
    while (jsonLength(state) > MAX_STATE_CHARS && missing.size() > 1) {
      missing.remove(missing.size() - 1);
    }
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

  private void clearInteractionContract() {
    interactionRequired = false;
    requiredInteractionCheckIds = new ArrayList<>();
    clearPendingBrowserRegression();
    clearBrowserInteractionEvidence();
  }

  private void clearPendingBrowserRegression() {
    pendingBrowserRegressionPlanId = "";
    pendingBrowserRegressionPlanHash = "";
  }

  private void clearBrowserInteractionEvidence() {
    browserScenarioIds = new ArrayList<>();
    browserPlanId = "";
    browserRevision = -1L;
    browserSourceRevision = "";
    browserTestPlanHash = "";
    browserVerifiedBehaviorEvidence = new ArrayList<>();
    evidence.removeIf(item ->
        item.role == CodeToolRole.QUALITY || "browser_test".equals(item.toolName));
  }

  private BrowserContract browserContract(ToolArguments arguments) {
    List<String> provided = new ArrayList<>();
    List<String> duplicates = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    boolean hasDynamicScenario = false;
    Object raw = arguments == null ? null : arguments.get("scenarios");
    if (raw instanceof List) {
      for (Object item : (List<?>) raw) {
        String id = item instanceof Map ? text(((Map<?, ?>) item).get("id")) : "";
        if (id.isEmpty()) continue;
        provided.add(id);
        if (!seen.add(id) && !duplicates.contains(id)) duplicates.add(id);
        if (hasDynamicScenario((Map<?, ?>) item)) hasDynamicScenario = true;
      }
    }
    List<String> missing = requiredInteractionCheckIds.isEmpty()
        ? new ArrayList<>() : difference(requiredInteractionCheckIds, seen);
    List<String> unexpected = requiredInteractionCheckIds.isEmpty()
        ? new ArrayList<>()
        : difference(new ArrayList<>(seen), new LinkedHashSet<>(requiredInteractionCheckIds));
    return new BrowserContract(provided, missing, unexpected, duplicates, hasDynamicScenario);
  }

  private static boolean hasDynamicScenario(Map<?, ?> scenario) {
    Object actions = scenario == null ? null : scenario.get("actions");
    if (!(actions instanceof Collection) || ((Collection<?>) actions).isEmpty()) return false;
    boolean hasUserAction = false;
    boolean hasDynamicCheckpoint = false;
    for (Object raw : (Collection<?>) actions) {
      if (!(raw instanceof Map)) continue;
      Map<?, ?> action = (Map<?, ?>) raw;
      String type = text(action.get("type"));
      if ("click".equals(type) || "input".equals(type)) hasUserAction = true;
      if ("wait_for".equals(type)) {
        Object expectation = action.get("expectation");
        if (expectation instanceof Map
            && "false_to_true".equals(text(((Map<?, ?>) expectation).get("transition")))) {
          hasDynamicCheckpoint = true;
        }
      }
    }
    if (!hasUserAction) return false;
    Object expectations = scenario.get("expectations");
    if (!(expectations instanceof Collection)) return false;
    for (Object raw : (Collection<?>) expectations) {
      if (raw instanceof Map
          && "false_to_true".equals(text(((Map<?, ?>) raw).get("transition")))) return true;
    }
    return hasDynamicCheckpoint;
  }

  private boolean captureBrowserTransaction(ToolArguments arguments, Map<String, Object> data) {
    BrowserContract contract = browserContract(arguments);
    if (!contract.valid(requiredInteractionCheckIds.isEmpty(), interactionRequired)
        || contract.provided.isEmpty()
        || !Boolean.TRUE.equals(data.get("passed"))
        || !"none".equals(text(data.get("failure_kind")))) return false;
    Object rawCoverage = data.get("coverage_summary");
    if (!(rawCoverage instanceof Map)) return false;
    Map<?, ?> coverage = (Map<?, ?>) rawCoverage;
    if (!Boolean.TRUE.equals(coverage.get("complete"))) return false;
    List<String> passedScenarioIds = stringList(coverage.get("passed_scenario_ids"));
    List<String> failedScenarioIds = stringList(coverage.get("failed_scenario_ids"));
    List<String> resultScenarioIds = passedScenarioResultIds(data.get("scenario_results"));
    String sourceRevision = text(data.get("source_revision"));
    String testPlanHash = text(data.get("test_plan_hash"));
    if (!failedScenarioIds.isEmpty()
        || !sameIds(contract.provided, passedScenarioIds)
        || resultScenarioIds == null
        || !sameIds(contract.provided, resultScenarioIds)) {
      return false;
    }
    browserScenarioIds = new ArrayList<>(contract.provided);
    browserPlanId = planId;
    browserRevision = revision;
    browserSourceRevision = sourceRevision;
    browserTestPlanHash = testPlanHash;
    browserVerifiedBehaviorEvidence = behaviorEvidence(arguments, data);
    return true;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> behaviorEvidence(
      ToolArguments arguments, Map<String, Object> data) {
    Map<String, Map<?, ?>> scenariosById = new LinkedHashMap<>();
    Object rawScenarios = arguments == null ? null : arguments.get("scenarios");
    if (rawScenarios instanceof List) {
      for (Object raw : (List<?>) rawScenarios) {
        if (!(raw instanceof Map)) continue;
        Map<?, ?> scenario = (Map<?, ?>) raw;
        String id = text(scenario.get("id"));
        if (!id.isEmpty()) scenariosById.put(id, scenario);
      }
    }
    List<Map<String, Object>> values = new ArrayList<>();
    Object rawResults = data.get("scenario_results");
    if (!(rawResults instanceof List)) return values;
    for (Object raw : (List<?>) rawResults) {
      if (!(raw instanceof Map)) continue;
      Map<?, ?> result = (Map<?, ?>) raw;
      String id = text(firstValue((Map<String, Object>) result, "id", "scenario_id"));
      if (id.isEmpty() || !Boolean.TRUE.equals(result.get("passed"))) continue;
      Map<String, Object> proof = new LinkedHashMap<>();
      proof.put("id", id);
      Map<?, ?> scenario = scenariosById.get(id);
      if (scenario != null) proof.put("description", text(scenario.get("description")));
      if (result.get("action_trace") instanceof List) {
        proof.put("action_trace", result.get("action_trace"));
      }
      if (result.get("actual_state") instanceof Map) {
        proof.put("actual_state", result.get("actual_state"));
      }
      values.add(proof);
    }
    return values;
  }

  private boolean hasCurrentBrowserTransaction() {
    return planId.equals(browserPlanId)
        && revision == browserRevision
        && !browserScenarioIds.isEmpty();
  }

  @SuppressWarnings("unchecked")
  private void decorateManagedResult(String toolName, Map<String, Object> data) {
    data.remove("required_interaction_check_ids");
    data.remove("covered_interaction_check_ids");
    data.remove("missing_interaction_check_ids");
    data.remove("interaction_audit");
    Object rawCoverage = data.get("coverage_summary");
    if (rawCoverage instanceof Map) {
      Map<String, Object> coverage = new LinkedHashMap<>((Map<String, Object>) rawCoverage);
      coverage.remove("required_interaction_check_ids");
      coverage.remove("covered_interaction_check_ids");
      coverage.remove("missing_interaction_check_ids");
      data.put("coverage_summary", coverage);
    }
    if ("browser_test".equals(toolName)) {
      data.remove("plan_id");
      data.remove("source_revision");
      data.remove("test_plan_hash");
      data.remove("cache_key");
    }
  }

  private Map<String, Object> preconditionData(String operation, String nextAction) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("operation", operation);
    data.put("missing_stage", nextAction);
    data.put("recommended_next_action", nextAction);
    data.put("plan_state", state());
    return data;
  }

  private static List<String> interactionCheckIds(Object raw) {
    List<String> out = new ArrayList<>();
    if (!(raw instanceof List)) return out;
    for (Object item : (List<?>) raw) {
      if (!(item instanceof Map)) continue;
      String id = text(((Map<?, ?>) item).get("check_id"));
      if (!id.isEmpty() && !out.contains(id)) out.add(id);
    }
    return out;
  }

  private static List<String> difference(List<String> expected, Set<String> actual) {
    List<String> out = new ArrayList<>();
    for (String id : expected) if (!actual.contains(id)) out.add(id);
    return out;
  }

  private static boolean sameIds(Collection<String> expected, Collection<String> actual) {
    return expected.size() == actual.size()
        && new LinkedHashSet<>(expected).equals(new LinkedHashSet<>(actual));
  }

  private static final class BrowserContract {
    final List<String> provided;
    final List<String> missing;
    final List<String> unexpected;
    final List<String> duplicates;
    final boolean hasDynamicScenario;

    BrowserContract(
        List<String> provided,
        List<String> missing,
        List<String> unexpected,
        List<String> duplicates,
        boolean hasDynamicScenario) {
      this.provided = provided;
      this.missing = missing;
      this.unexpected = unexpected;
      this.duplicates = duplicates;
      this.hasDynamicScenario = hasDynamicScenario;
    }

    boolean valid(boolean idsOptional, boolean dynamicRequired) {
      return (idsOptional || (missing.isEmpty() && unexpected.isEmpty()))
          && duplicates.isEmpty()
          && (!dynamicRequired || hasDynamicScenario);
    }
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

  private static final class ReadCoverage {
    final String sourceRevision;
    final List<String> evidenceIds;

    ReadCoverage(String sourceRevision, Collection<String> evidenceIds) {
      this.sourceRevision = sourceRevision;
      this.evidenceIds = Collections.unmodifiableList(new ArrayList<>(evidenceIds));
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
