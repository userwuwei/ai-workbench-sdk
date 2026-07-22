package com.cscjapp.aiworkbench.codeagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.AgentRunContext;
import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ContextProvider;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.PromptContext;
import com.cscjapp.aiworkbench.api.PromptContributor;
import com.cscjapp.aiworkbench.api.TaskValidator;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolCallback;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolInvocation;
import com.cscjapp.aiworkbench.api.ToolPolicy;
import com.cscjapp.aiworkbench.api.ToolPolicyDecision;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;
import com.cscjapp.aiworkbench.api.ValidationContext;
import com.cscjapp.aiworkbench.api.ValidationResult;
import com.cscjapp.aiworkbench.api.WorkbenchDefinition;
import com.cscjapp.aiworkbench.api.WorkbenchEvent;
import com.cscjapp.aiworkbench.api.WorkbenchHost;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.core.AgentEngine;
import com.cscjapp.aiworkbench.core.AgentMessage;
import com.cscjapp.aiworkbench.core.AgentObserver;
import com.cscjapp.aiworkbench.core.AgentToolCall;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.ModelResponse;
import com.cscjapp.aiworkbench.core.PromptComposer;
import com.cscjapp.aiworkbench.tools.file.FileToolSet;
import com.cscjapp.aiworkbench.tools.file.LocalWorkspaceAccess;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class CodeAgentPresetTest {
  private File root;
  private LocalWorkspaceAccess workspace;

  @Before
  public void setUp() throws Exception {
    root = Files.createTempDirectory("aiw-code-agent").toFile();
    Files.write(
        new File(root, "main.txt").toPath(),
        "before".getBytes(StandardCharsets.UTF_8));
    workspace = new LocalWorkspaceAccess("workspace", root);
  }

  @After
  public void tearDown() {
    delete(root);
  }

  @Test
  public void commonPromptIsLanguageNeutralAndProfileRulesRemainSeparate() {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile("language-specific-rules"))
            .workspace(workspace)
            .languageTools(FileToolSet.standard(workspace))
            .build();
    String composed =
        new PromptComposer()
            .compose(
                definition(preset),
                new PromptContext(
                    "workspace",
                    "task",
                    Collections.singletonMap("native_tools", true)));
    String common =
        preset.promptContributors().get(0).contribute(
                new PromptContext("workspace", "task", Collections.emptyMap()))
            .get(0)
            .content();
    assertFalse(common.contains("HTML"));
    assertFalse(common.contains("Python"));
    assertFalse(common.contains("WebView"));
    assertFalse(common.contains("src/index"));
    assertTrue(common.contains("finalize_task"));
    assertTrue(common.contains("create_file 只用于当前项目尚不存在的新路径"));
    assertTrue(common.contains("都必须使用 search_replace"));
    assertTrue(common.contains("planned_files 只表示任务涉及的文件"));
    assertTrue(common.contains("search_replace.replacements[]"));
    assertFalse(common.contains("create_file 用于写入模型已经生成的完整内容"));
    assertFalse(common.contains("目标冲突由本地用户决定覆盖或新建"));
    assertTrue(composed.contains("language-specific-rules"));
  }

  @Test
  public void planningModesExposeStableLanguageNeutralInstructions() {
    String adaptive = commonPrompt(CodePlanningMode.ADAPTIVE);
    String force = commonPrompt(CodePlanningMode.FORCE);
    String skip = commonPrompt(CodePlanningMode.SKIP);

    assertTrue(adaptive.contains("采用自适应规划"));
    assertTrue(adaptive.contains("多文件修改"));
    assertTrue(force.contains("首次写入前调用 plan_task"));
    assertTrue(skip.contains("不要求 plan_task"));
    for (String prompt : Arrays.asList(adaptive, force, skip)) {
      assertFalse(prompt.contains("HTML"));
      assertFalse(prompt.contains("Python"));
      assertFalse(prompt.contains("WebView"));
    }
    assertEquals(CodePlanningMode.ADAPTIVE, CodePlanningMode.from(null));
    assertEquals(CodePlanningMode.ADAPTIVE, CodePlanningMode.from("unknown"));
    assertEquals(CodePlanningMode.FORCE, CodePlanningMode.from("force"));
    assertEquals(CodePlanningMode.SKIP, CodePlanningMode.from("SKIP"));
  }

  @Test
  public void planSchemaIsShortAndStructurallyExplicit() {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .build();
    Map<String, Object> schema =
        find(preset.tools(), CodeAgentToolNames.PLAN_TASK).spec().inputSchema();
    Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
    Map<?, ?> steps = (Map<?, ?>) properties.get("steps");
    Map<?, ?> stepItem = (Map<?, ?>) steps.get("items");
    Map<?, ?> stepProperties = (Map<?, ?>) stepItem.get("properties");
    Map<?, ?> files = (Map<?, ?>) properties.get("planned_files");

    assertEquals(3, steps.get("minItems"));
    assertEquals(5, steps.get("maxItems"));
    assertEquals(8, files.get("maxItems"));
    assertTrue(stepProperties.containsKey("id"));
    assertTrue(stepProperties.containsKey("title"));
    assertTrue(stepProperties.containsKey("phase"));
    assertTrue(stepProperties.containsKey("required_tools"));
    assertTrue(stepProperties.containsKey("acceptance"));
    assertTrue(((List<?>) schema.get("required")).containsAll(
        Arrays.asList(
            "goal", "quality_mode", "writing_mode", "planned_files",
            "verification_plan", "steps")));
  }

  @Test
  public void legacyAndMalformedPlansNormalizeWithoutRepairRound() {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .build();
    CodePlanNormalizer normalizer = new CodePlanNormalizer(contract);
    Map<String, Object> normalized =
        normalizer.normalize(
            map(
                "goal", "构建移动端交互",
                "quality_mode", "interface_product",
                "verification_plan",
                Arrays.asList("syntax_check", "browser_test: 启动和重开"),
                "quality_bar", "保持交互完整且移动端不溢出",
                "interface_design_spec", "单列布局，按钮适配触控",
                "steps",
                Collections.singletonList(
                    map("step", "1", "action", "完成核心游戏实现"))));

    List<?> steps = (List<?>) normalized.get("steps");
    assertTrue(steps.size() >= 3 && steps.size() <= 5);
    assertEquals("完成核心游戏实现", ((Map<?, ?>) steps.get(0)).get("title"));
    assertEquals("step-1", ((Map<?, ?>) steps.get(0)).get("id"));
    assertEquals("保持交互完整且移动端不溢出", normalized.get("quality_bar"));
    assertEquals("单列布局，按钮适配触控", normalized.get("interface_design_spec"));
    Map<?, ?> verify = findStep(steps, "verify");
    assertEquals(Arrays.asList("syntax_check", "browser_test"), verify.get("required_tools"));

    Map<String, Object> malformed =
        normalizer.normalize(
            map(
                "goal", "修复页面",
                "steps", "[{\"step\":\"1\"}]",
                "planned_files", "index.html"));
    assertEquals(4, ((List<?>) malformed.get("steps")).size());
    assertEquals("targeted_edit", malformed.get("writing_mode"));
  }

  @Test
  public void metaSchemaExtensionMergesWithoutReplacingBaseProperties() {
    Map<String, Object> extraProperty = new LinkedHashMap<>();
    extraProperty.put("type", "string");
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("runtime_metric", extraProperty);
    Map<String, Object> extension = new LinkedHashMap<>();
    extension.put("properties", properties);
    Map<String, Map<String, Object>> extensions = new LinkedHashMap<>();
    extensions.put(CodeAgentToolNames.QUALITY_REVIEW, extension);

    CodeLanguageProfile profile =
        CodeLanguageProfile.builder("test")
            .verificationContract(CodeValidationContract.builder().build())
            .metaToolExtensions(extensions)
            .build();
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile).workspace(workspace).build();
    ToolSpec quality = find(preset.tools(), CodeAgentToolNames.QUALITY_REVIEW).spec();
    Map<?, ?> schemaProperties = (Map<?, ?>) quality.inputSchema().get("properties");
    assertTrue(schemaProperties.containsKey("passed"));
    assertTrue(schemaProperties.containsKey("review_target"));
    assertTrue(schemaProperties.containsKey("dimension_reviews"));
    assertTrue(schemaProperties.containsKey("evidence_checked"));
    assertTrue(schemaProperties.containsKey("runtime_metric"));
    Map<?, ?> planProperties =
        (Map<?, ?>)
            find(preset.tools(), CodeAgentToolNames.PLAN_TASK)
                .spec()
                .inputSchema()
                .get("properties");
    assertTrue(planProperties.containsKey("task_type"));
    assertTrue(planProperties.containsKey("implementation_shape"));
  }

  @Test
  public void editRequiresCurrentRunReadButCreateDoesNot() throws Exception {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("create_file", CodeToolRole.CREATE);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentTool read = tool("read_file");
    AgentTool edit = tool("search_replace");
    AgentTool create = tool("create_file");
    ToolArguments path =
        new ToolArguments(Collections.singletonMap("path", "main.txt"));

    AgentRunContext first = new AgentRunContext(1L, "s", "workspace", "task");
    policy.onRunStarted(first);
    assertFalse(policy.supports(new ToolInvocation("c", create, path)));
    ToolPolicyDecision blocked =
        decision(policy, new ToolInvocation("e1", edit, path));
    assertEquals(ToolPolicyDecision.Kind.ERROR, blocked.kind());
    assertTrue(blocked.result().retryable());
    policy.onToolCompleted(
        first,
        new ToolInvocation("r", read, path),
        ToolResult.success(Collections.singletonMap("path", "main.txt")));
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, new ToolInvocation("e2", edit, path)).kind());

    policy.onRunStarted(new AgentRunContext(2L, "s", "workspace", "next"));
    assertEquals(
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, new ToolInvocation("e3", edit, path)).kind());
  }

  @Test
  public void batchReadEvidenceAuthorizesOnlyFilesActuallyReturned() throws Exception {
    for (String name : Arrays.asList("index.html", "style.css", "game.js")) {
      Files.write(
          new File(root, name).toPath(),
          name.getBytes(StandardCharsets.UTF_8));
    }
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_file_batch", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentTool batch = tool("read_file_batch");
    AgentTool read = tool("read_file");
    AgentTool edit = tool("search_replace");
    AgentRunContext run = new AgentRunContext(7L, "s", "workspace", "task");
    policy.onRunStarted(run);

    ToolArguments batchArguments =
        new ToolArguments(
            map(
                "path",
                root.getAbsolutePath(),
                "targets",
                Arrays.asList(
                    map("path", "index.html"),
                    map("path", "style.css"),
                    map("path", "game.js"))));
    policy.onToolCompleted(
        run,
        new ToolInvocation("batch", batch, batchArguments),
        ToolResult.success(
            map(
                "path",
                root.getAbsolutePath(),
                "items",
                Arrays.asList(
                    map("result", map("path", "index.html")),
                    map("result", map("resolved_path", "style.css"))),
                "truncated",
                true)));

    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, editInvocation("index.html", edit)).kind());
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, editInvocation("style.css", edit)).kind());
    ToolPolicyDecision missing = decision(policy, editInvocation("game.js", edit));
    assertEquals(ToolPolicyDecision.Kind.ERROR, missing.kind());
    assertEquals("read_evidence_required", missing.result().errorCode());
    assertTrue(missing.result().message().contains("批量读取中被截断"));

    policy.onToolCompleted(
        run,
        new ToolInvocation(
            "read-game",
            read,
            new ToolArguments(Collections.singletonMap("path", "game.js"))),
        ToolResult.success(Collections.singletonMap("path", "game.js")));
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, editInvocation("game.js", edit)).kind());
  }

  @Test
  public void explicitReadPathsAuthorizeGenericReadResults() throws Exception {
    File style = new File(root, "style.css");
    Files.write(style.toPath(), "body{}".getBytes(StandardCharsets.UTF_8));
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("custom_batch_read", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentRunContext run = new AgentRunContext(8L, "s", "workspace", "task");
    policy.onRunStarted(run);
    policy.onToolCompleted(
        run,
        new ToolInvocation(
            "custom",
            tool("custom_batch_read"),
            new ToolArguments(Collections.singletonMap("path", root.getAbsolutePath()))),
        ToolResult.success(
            Collections.singletonMap(
                "read_paths", Collections.singletonList(style.getAbsolutePath()))));

    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, editInvocation("style.css", tool("search_replace"))).kind());
  }

  private static ToolInvocation editInvocation(String path, AgentTool edit) {
    return new ToolInvocation(
        "edit-" + path,
        edit,
        new ToolArguments(Collections.singletonMap("path", path)));
  }

  @Test
  public void validationContractRequiresTerminalEvidenceVerificationAndQuality() {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("compile_test")
            .exemptCompletionTypes("explain")
            .requireQualityReview("ui_product")
            .build();
    CodeCompletionValidator validator = new CodeCompletionValidator(contract);

    ValidationResult missing = validate(validator, Collections.emptyList());
    assertFalse(missing.passed());
    assertTrue(hasIssue(missing, "finalize_task_missing"));

    List<ToolResult> uiEvidence = new ArrayList<>();
    uiEvidence.add(evidence("compile_test", Collections.singletonMap("passed", true)));
    uiEvidence.add(
        evidence(
            CodeAgentToolNames.QUALITY_REVIEW,
            map("passed", false, "blocking_gaps", Collections.singletonList("gap"))));
    uiEvidence.add(finalizeEvidence("completed", "ui_product"));
    ValidationResult failedQuality = validate(validator, uiEvidence);
    assertTrue(hasIssue(failedQuality, "quality_review_failed"));

    uiEvidence.set(
        1,
        evidence(
            CodeAgentToolNames.QUALITY_REVIEW,
            map(
                "passed",
                true,
                "minimal_version_risk",
                false,
                "blocking_gaps",
                Collections.emptyList())));
    assertTrue(validate(validator, uiEvidence).passed());
    List<ToolResult> latestFailure = new ArrayList<>(uiEvidence);
    latestFailure.add(
        ToolResult.error(
            "compile_failed",
            "failed",
            true,
            map("operation", "compile_test", "passed", false)));
    assertTrue(hasIssue(validate(validator, latestFailure), "compile_test_failed"));
    assertTrue(
        validate(
                validator,
                Collections.singletonList(finalizeEvidence("completed", "explain")))
            .passed());
    assertTrue(
        validate(
                validator,
                Collections.singletonList(finalizeEvidence("blocked", "")))
            .passed());
  }

  @Test
  public void managedPlanAdvancesOnlyFromValidCurrentRunEvidence() {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .requireQualityReview("ui_product")
            .requireManagedPlan("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put(CodeAgentToolNames.QUALITY_REVIEW, CodeToolRole.QUALITY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles);
    AgentRunContext run = new AgentRunContext(11L, "session", "workspace", "task");
    coordinator.onRunStarted(run);
    long generation = coordinator.generation();
    Map<String, Object> planResult =
        coordinator.acceptPlan(
            map(
                "goal", "实现交互产品",
                "quality_mode", "interface_product",
                "verification_plan", Arrays.asList("syntax_check", "browser_test"),
                "steps",
                Arrays.asList(
                    step("discover", "读取上下文", "discover", "read_file"),
                    step("implement", "完成实现", "implement", "search_replace"),
                    step("verify", "执行验证", "verify", "syntax_check", "browser_test"),
                    step("quality", "质量审查", "quality", "quality_review"))));
    assertEquals("discover", currentStepId(planResult.get("plan_state")));
    assertTrue(String.valueOf(planResult.get("plan_state")).length() <= 800);

    ToolResult read = coordinator.recordAndDecorate(
        generation, "read_file", ToolResult.success(map("content", "source")));
    assertEquals("implement", currentStepId(read.data().get("plan_state")));
    ToolResult duplicateRead = coordinator.recordAndDecorate(
        generation, "read_file", ToolResult.success(map("content", "source")));
    assertFalse(duplicateRead.data().containsKey("plan_state"));

    coordinator.recordAndDecorate(
        generation, "search_replace", ToolResult.success(map("changed", false)));
    assertFalse(coordinator.isComplete());
    ToolResult edited = coordinator.recordAndDecorate(
        generation, "search_replace", ToolResult.success(map("changed", true)));
    assertEquals("verify", currentStepId(edited.data().get("plan_state")));

    coordinator.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));
    coordinator.recordAndDecorate(
        generation, "browser_test", ToolResult.success(map("passed", false)));
    assertFalse(coordinator.isComplete());
    ToolResult verified = coordinator.recordAndDecorate(
        generation, "browser_test", ToolResult.success(map("passed", true)));
    assertEquals("quality", currentStepId(verified.data().get("plan_state")));

    coordinator.recordAndDecorate(
        generation,
        "quality_review",
        ToolResult.success(
            map(
                "passed", true,
                "blocking_gaps", "still blocked",
                "minimal_version_risk", false)));
    assertFalse(coordinator.isComplete());
    coordinator.recordAndDecorate(
        generation,
        "quality_review",
        ToolResult.success(
            map(
                "passed", true,
                "blocking_gaps", Collections.emptyList(),
                "claimed_but_unsupported", Collections.emptyList(),
                "minimal_version_risk", false)));
    assertTrue(coordinator.isComplete());

    ToolResult changedAgain = coordinator.recordAndDecorate(
        generation, "search_replace", ToolResult.success(map("changed", true)));
    assertFalse(coordinator.isComplete());
    assertEquals("verify", currentStepId(changedAgain.data().get("plan_state")));

    coordinator.onRunStarted(new AgentRunContext(12L, "session", "workspace", "next"));
    ToolResult late = coordinator.recordAndDecorate(
        generation, "browser_test", ToolResult.success(map("passed", true)));
    assertFalse(late.data().containsKey("plan_state"));
    assertFalse(coordinator.hasPlan());
  }

  @Test
  public void forcePlanBlocksWritesButSkipDoesNotBypassVerification() {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check")
            .requireManagedPlan("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("search_replace", CodeToolRole.EDIT);
    ManagedCodePlanCoordinator force =
        new ManagedCodePlanCoordinator(CodePlanningMode.FORCE, contract, roles);
    force.onRunStarted(new AgentRunContext(21L, "s", "w", "task"));
    ToolInvocation edit =
        new ToolInvocation("edit", tool("search_replace"), ToolArguments.empty());
    assertTrue(force.supports(edit));
    AtomicReference<ToolPolicyDecision> decision = new AtomicReference<>();
    force.evaluate(null, edit, decision::set);
    assertEquals("managed_plan_required", decision.get().result().errorCode());
    assertTrue(decision.get().result().retryable());

    ManagedCodePlanCoordinator skip =
        new ManagedCodePlanCoordinator(CodePlanningMode.SKIP, contract, roles);
    skip.onRunStarted(new AgentRunContext(22L, "s", "w", "task"));
    assertFalse(skip.supports(edit));
    CodeCompletionValidator validator = new CodeCompletionValidator(contract, skip);
    ValidationResult missingVerification =
        validate(
            validator,
            Collections.singletonList(finalizeEvidence("completed", "ui_product")));
    assertFalse(missingVerification.passed());
    assertTrue(hasIssue(missingVerification, "syntax_check_missing"));
    assertFalse(hasIssue(missingVerification, "managed_plan_missing"));
    assertTrue(validate(
        validator,
        Collections.singletonList(finalizeEvidence("needs_user_input", "ui_product"))).passed());
  }

  @Test
  public void completedRejectsVerificationAndQualityFromBeforeLatestWrite() {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .requireManagedPlan("ui_product")
            .requireQualityReview("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles);
    coordinator.onRunStarted(new AgentRunContext(31L, "s", "w", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "ui",
            "quality_mode", "interface_product",
            "verification_plan", Arrays.asList("syntax_check", "browser_test"),
            "steps", Collections.emptyList()));
    ToolResult read = ToolResult.success(map("operation", "read_file", "content", "source"));
    ToolResult edit = ToolResult.success(map("operation", "search_replace", "changed", true));
    ToolResult syntax = ToolResult.success(map("operation", "syntax_check", "passed", true));
    ToolResult browser = ToolResult.success(map("operation", "browser_test", "passed", true));
    ToolResult quality = ToolResult.success(
        map(
            "operation", "quality_review",
            "passed", true,
            "blocking_gaps", Collections.emptyList(),
            "claimed_but_unsupported", Collections.emptyList(),
            "minimal_version_risk", false));
    coordinator.recordAndDecorate(generation, "read_file", read);
    coordinator.recordAndDecorate(generation, "search_replace", edit);
    coordinator.recordAndDecorate(generation, "syntax_check", syntax);
    coordinator.recordAndDecorate(generation, "browser_test", browser);
    coordinator.recordAndDecorate(generation, "quality_review", quality);
    CodeCompletionValidator validator = new CodeCompletionValidator(contract, coordinator);
    List<ToolResult> evidence = new ArrayList<>(Arrays.asList(
        read, edit, syntax, browser, quality, finalizeEvidence("completed", "ui_product")));
    assertTrue(validate(validator, evidence).passed());

    coordinator.recordAndDecorate(generation, "search_replace", edit);
    evidence.add(evidence.size() - 1, edit);
    ValidationResult stale = validate(validator, evidence);
    assertFalse(stale.passed());
    assertTrue(hasIssue(stale, "managed_plan_incomplete"));
    assertTrue(hasIssue(stale, "browser_test_missing"));
    assertTrue(hasIssue(stale, "quality_review_missing"));
  }

  @Test
  public void nativeAndLegacyCannotBypassFinalizeTool() {
    assertEquals(2, terminalRounds(true));
    assertEquals(2, terminalRounds(false));
  }

  @Test
  public void nativeAndLegacyPlansProduceTheSameNormalizedShape() {
    assertEquals(normalizedPlanRound(true), normalizedPlanRound(false));
  }

  private Map<String, Object> normalizedPlanRound(boolean nativePlan) {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .build();
    AtomicInteger rounds = new AtomicInteger();
    ModelGateway gateway =
        (request, observer) -> {
          if (rounds.incrementAndGet() == 1) {
            Map<String, Object> args =
                map(
                    "goal", "implement",
                    "steps", Collections.singletonList(map("step", "1", "action", "edit")));
            if (nativePlan) {
              observer.onComplete(
                  new ModelResponse(
                      "",
                      "tool_calls",
                      Collections.singletonList(
                          new AgentToolCall(
                              "plan", CodeAgentToolNames.PLAN_TASK, new ToolArguments(args)))));
            } else {
              observer.onComplete(
                  new ModelResponse(
                      "{\"next_action\":{\"tool\":\"plan_task\",\"args\":"
                          + "{\"goal\":\"implement\",\"steps\":[{\"step\":\"1\",\"action\":\"edit\"}]}}}",
                      "stop",
                      Collections.emptyList()));
            }
          } else {
            observer.onComplete(
                new ModelResponse(
                    "",
                    "tool_calls",
                    Collections.singletonList(
                        new AgentToolCall(
                            "final",
                            CodeAgentToolNames.FINALIZE_TASK,
                            new ToolArguments(
                                map("status", "blocked", "summary", "done"))))));
          }
          return Cancellable.NONE;
        };
    AgentEngine engine =
        new AgentEngine(
            definition(preset),
            gateway,
            new ModelEndpoint("http://localhost", "", "model", 0.2, nativePlan, false),
            (request, callback) -> Cancellable.NONE,
            Runnable::run,
            "session",
            "workspace",
            false,
            5);
    AtomicReference<Map<String, Object>> normalized = new AtomicReference<>();
    engine.submit(
        "task",
        new AgentObserver() {
          @Override public void onState(String state) {}
          @Override public void onDelta(String content, String reasoning) {}
          @Override public void onToolStarted(String id, String name, ToolArguments arguments) {}
          @Override public void onToolProgress(
              String id, String stage, long current, long total, String message) {}
          @Override public void onToolCompleted(String id, String name, ToolResult result) {
            if (CodeAgentToolNames.PLAN_TASK.equals(name)) {
              Object value = result.data().get("normalized_plan");
              if (value instanceof Map) normalized.set(new LinkedHashMap<>((Map<String, Object>) value));
            }
          }
          @Override public void onValidation(ValidationResult result) {}
          @Override public void onFinal(String content) {}
          @Override public void onError(Throwable error) { throw new AssertionError(error); }
        });
    assertNotNull(normalized.get());
    return normalized.get();
  }

  private int terminalRounds(boolean nativeTools) {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .languageTools(FileToolSet.standard(workspace))
            .build();
    AtomicInteger rounds = new AtomicInteger();
    ModelGateway gateway =
        (request, observer) -> {
          if (rounds.incrementAndGet() == 1) {
            String content =
                nativeTools
                    ? "普通文本"
                    : "{\"next_action\":{\"tool\":\"final\",\"args\":{\"summary\":\"伪终态\"}}}";
            observer.onComplete(new ModelResponse(content, "stop", Collections.emptyList()));
          } else {
            observer.onComplete(
                new ModelResponse(
                    "",
                    "tool_calls",
                    Collections.singletonList(
                        new AgentToolCall(
                            "final",
                            CodeAgentToolNames.FINALIZE_TASK,
                            new ToolArguments(
                                map(
                                    "status",
                                    "completed",
                                    "completion_type",
                                    "explain",
                                    "summary",
                                    "done"))))));
          }
          return Cancellable.NONE;
        };
    AgentEngine engine =
        new AgentEngine(
            definition(preset),
            gateway,
            new ModelEndpoint("http://localhost", "", "model", 0.2, nativeTools, false),
            (request, callback) -> Cancellable.NONE,
            Runnable::run,
            "session",
            "workspace",
            false,
            5);
    AtomicReference<String> output = new AtomicReference<>();
    engine.submit("explain", observer(output));
    assertEquals("done", output.get());
    return rounds.get();
  }

  private CodeLanguageProfile profile(String rules) {
    return CodeLanguageProfile.builder("test")
        .languageRules(rules)
        .verificationContract(
            CodeValidationContract.builder().exemptCompletionTypes("explain").build())
        .build();
  }

  private String commonPrompt(CodePlanningMode mode) {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .planningMode(mode)
            .build();
    return preset.promptContributors().get(0)
        .contribute(new PromptContext("workspace", "task", Collections.emptyMap()))
        .get(0)
        .content();
  }

  private WorkbenchDefinition definition(CodeAgentPreset preset) {
    return new WorkbenchDefinition() {
      @Override
      public String id() {
        return "test";
      }

      @Override
      public String displayName() {
        return "test";
      }

      @Override
      public List<PromptContributor> promptContributors() {
        return preset.promptContributors();
      }

      @Override
      public List<ContextProvider> contextProviders() {
        return Collections.emptyList();
      }

      @Override
      public List<AgentTool> tools() {
        return preset.tools();
      }

      @Override
      public List<ToolPolicy> toolPolicies() {
        return preset.toolPolicies();
      }

      @Override
      public List<TaskValidator> validators() {
        return preset.validators();
      }

      @Override
      public WorkbenchHost host() {
        return new WorkbenchHost() {
          @Override
          public void openArtifact(String artifactId) {}

          @Override
          public void refreshArtifacts() {}

          @Override
          public void handleAction(String actionId, ToolArguments arguments) {}

          @Override
          public void onEvent(WorkbenchEvent event) {}
        };
      }
    };
  }

  private static AgentTool find(List<AgentTool> tools, String name) {
    for (AgentTool tool : tools) if (name.equals(tool.spec().name())) return tool;
    return null;
  }

  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec(name, name, Collections.singletonMap("type", "object"));
      }

      @Override
      public Cancellable execute(
          ToolContext context, ToolArguments arguments, ToolCallback callback) {
        callback.onComplete(ToolResult.success());
        return Cancellable.NONE;
      }
    };
  }

  private static ToolPolicyDecision decision(
      ReadBeforeEditPolicy policy, ToolInvocation invocation) {
    AtomicReference<ToolPolicyDecision> result = new AtomicReference<>();
    policy.evaluate(null, invocation, result::set);
    return result.get();
  }

  private static ToolResult evidence(String operation, Map<String, ?> values) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (values != null) data.putAll(values);
    data.put("operation", operation);
    return ToolResult.success(data);
  }

  private static ToolResult finalizeEvidence(String status, String completionType) {
    return evidence(
        CodeAgentToolNames.FINALIZE_TASK,
        map("status", status, "completion_type", completionType));
  }

  private static ValidationResult validate(
      CodeCompletionValidator validator, List<ToolResult> evidence) {
    AtomicReference<ValidationResult> output = new AtomicReference<>();
    validator.validate(
        new ValidationContext("session", "workspace", "task", evidence), output::set);
    return output.get();
  }

  private static boolean hasIssue(ValidationResult result, String code) {
    return result.issues().stream().anyMatch(issue -> code.equals(issue.code()));
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> output = new LinkedHashMap<>();
    for (int index = 0; index + 1 < values.length; index += 2) {
      output.put(String.valueOf(values[index]), values[index + 1]);
    }
    return output;
  }

  private static Map<String, Object> step(
      String id, String title, String phase, String... requiredTools) {
    return map(
        "id", id,
        "title", title,
        "phase", phase,
        "required_tools", Arrays.asList(requiredTools),
        "acceptance", Collections.singletonList(title));
  }

  private static Map<?, ?> findStep(List<?> steps, String phase) {
    for (Object raw : steps) {
      if (raw instanceof Map && phase.equals(((Map<?, ?>) raw).get("phase"))) {
        return (Map<?, ?>) raw;
      }
    }
    throw new AssertionError("missing phase " + phase);
  }

  private static String currentStepId(Object rawState) {
    if (!(rawState instanceof Map)) return "";
    Object current = ((Map<?, ?>) rawState).get("current_step");
    if (!(current instanceof Map)) return "";
    Object id = ((Map<?, ?>) current).get("id");
    return id == null ? "" : String.valueOf(id);
  }

  private static AgentObserver observer(AtomicReference<String> output) {
    return new AgentObserver() {
      @Override
      public void onState(String state) {}

      @Override
      public void onDelta(String content, String reasoning) {}

      @Override
      public void onToolStarted(String id, String name, ToolArguments arguments) {}

      @Override
      public void onToolProgress(
          String id, String stage, long current, long total, String message) {}

      @Override
      public void onToolCompleted(String id, String name, ToolResult result) {}

      @Override
      public void onValidation(ValidationResult result) {}

      @Override
      public void onFinal(String content) {
        output.set(content);
      }

      @Override
      public void onError(Throwable error) {
        throw new AssertionError(error);
      }
    };
  }

  private static void delete(File file) {
    if (file == null || !file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) delete(child);
    }
    file.delete();
  }
}
