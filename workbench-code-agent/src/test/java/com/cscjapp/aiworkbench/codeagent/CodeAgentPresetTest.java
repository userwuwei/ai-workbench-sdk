package com.cscjapp.aiworkbench.codeagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.AgentRunContext;
import com.cscjapp.aiworkbench.api.AgentRoundContext;
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
import com.cscjapp.aiworkbench.api.ToolSelection;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  public void readPlanPromptProtocolIsEnabledOnlyWhenToolIsRegistered() {
    CodeAgentPreset withoutReadPlan =
        CodeAgentPreset.builder(profile("")).workspace(workspace).build();
    CodeAgentPreset withReadPlan =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .languageTools(Collections.singletonList(tool("read_plan")))
            .build();
    CodeAgentPreset legacyReadPrompt =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .languageTools(Collections.singletonList(tool("read_plan")))
            .convergentReadPrompt(false)
            .build();
    PromptContext context = new PromptContext("workspace", "task", Collections.emptyMap());
    String absent = withoutReadPlan.promptContributors().get(0).contribute(context).get(0).content();
    String present = withReadPlan.promptContributors().get(0).contribute(context).get(0).content();
    String legacy = legacyReadPrompt.promptContributors().get(0)
        .contribute(context).get(0).content();

    assertFalse(absent.contains("evidence_requirements"));
    assertTrue(present.contains("evidence_requirements"));
    assertTrue(present.contains("ready_for_edit=true"));
    assertTrue(present.contains("最小充分证据"));
    assertTrue(present.contains("已有逐字准确的 old 锚点时，直接编辑或验证"));
    assertTrue(present.contains("只调用一次 read_plan"));
    assertTrue(present.contains("禁止猜测行号"));
    assertFalse(present.contains("优先只传 path 使用 read_file 完整读取"));
    assertTrue(legacy.contains("优先只传 path 使用 read_file 完整读取"));
    assertFalse(legacy.contains("最小充分证据"));
  }

  @Test
  public void browserPromptUsesCanonicalShapesAndStableStateGuidance() {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .languageTools(Collections.singletonList(tool("browser_test")))
            .build();
    String prompt = preset.promptContributors().get(0)
        .contribute(new PromptContext("workspace", "task", Collections.emptyMap()))
        .get(0).content();

    assertTrue(prompt.contains("wait_for 只能位于 actions[]"));
    assertTrue(prompt.contains("eventually_true/false_to_true 只能写 transition"));
    assertTrue(prompt.contains("自动变化使用 actions=[]"));
    assertTrue(prompt.contains("禁止添加无因果点击凑交互"));
    assertTrue(prompt.contains("recommended_next_action"));
    assertTrue(prompt.contains("不是其他安全工具的执行禁令"));
    assertTrue(prompt.contains("允许在 syntax_check 前连续执行多次 search_replace"));
    assertTrue(prompt.contains("逐字来自当前 revision"));
    assertTrue(prompt.contains("动作后的关闭、消失或复位使用 eventually_true"));
    assertTrue(prompt.contains("‘可见且可点击’只能作为静态检查"));
    assertTrue(prompt.contains("同时存在 reading_brief 与 test_retry_brief"));
    assertTrue(prompt.contains("只有纯产品失败要求保持"));
    assertTrue(prompt.contains("禁止修改产品迎合测试"));
  }

  @Test
  public void malformedNativePlanArgumentsFailBeforeNormalization() {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .build();
    AgentTool plan = find(preset.tools(), CodeAgentToolNames.PLAN_TASK);
    AtomicReference<ToolResult> output = new AtomicReference<>();

    plan.execute(
        null,
        new ToolArguments(map("__raw_arguments", "{\"goal\":\"repair\"}}")),
        new ToolCallback() {
          @Override
          public void onProgress(String stage, long current, long total, String message) {}

          @Override
          public void onComplete(ToolResult result) {
            output.set(result);
          }
        });

    assertNotNull(output.get());
    assertEquals(ToolResult.Status.ERROR, output.get().status());
    assertEquals("invalid_tool_arguments", output.get().errorCode());
    assertTrue(output.get().retryable());
    assertFalse(((ManagedCodePlanCoordinator) preset.toolPolicies().get(0)).hasPlan());
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
    assertTrue(stepProperties.containsKey("file_refs"));
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
    assertEquals(3, ((List<?>) malformed.get("steps")).size());
    assertEquals("targeted_edit", malformed.get("writing_mode"));
  }

  @Test
  public void plannedFilesReceiveStableIdsAndImplementationMappings() {
    CodePlanNormalizer normalizer =
        new CodePlanNormalizer(
            CodeValidationContract.builder()
                .defaultRequiredEvidence("syntax_check", "browser_test")
                .build());
    Map<String, Object> source =
        map(
            "goal", "调整两个文件",
            "planned_files",
            Arrays.asList(
                map("path", "index.html", "action", "edit"),
                map("path", "styles.css", "action", "edit")),
            "steps",
            Arrays.asList(
                step("index-step", "修改页面", "implement", "search_replace", "rewrite"),
                step("style-step", "修改样式", "implement", "search_replace", "rewrite")));
    Map<String, Object> first = normalizer.normalize(source);
    Map<String, Object> second = normalizer.normalize(source);
    List<?> files = (List<?>) first.get("planned_files");
    List<?> repeatedFiles = (List<?>) second.get("planned_files");
    String indexId = String.valueOf(((Map<?, ?>) files.get(0)).get("file_id"));
    String styleId = String.valueOf(((Map<?, ?>) files.get(1)).get("file_id"));

    assertEquals(indexId, ((Map<?, ?>) repeatedFiles.get(0)).get("file_id"));
    assertEquals(styleId, ((Map<?, ?>) repeatedFiles.get(1)).get("file_id"));
    assertFalse(indexId.equals(styleId));
    assertEquals(
        Collections.singletonList(indexId),
        ((Map<?, ?>) ((List<?>) first.get("steps")).get(0)).get("file_refs"));
    assertEquals(
        Collections.singletonList(styleId),
        ((Map<?, ?>) ((List<?>) first.get("steps")).get(1)).get("file_refs"));

    Map<String, Object> explicit =
        normalizer.normalize(
            map(
                "goal", "显式映射",
                "planned_files", source.get("planned_files"),
                "steps",
                Collections.singletonList(
                    map(
                        "id", "implement",
                        "title", "完成实现",
                        "phase", "implement",
                        "file_refs", Arrays.asList("styles.css", "index.html")))));
    assertEquals(
        Arrays.asList(styleId, indexId),
        ((Map<?, ?>) ((List<?>) explicit.get("steps")).get(0)).get("file_refs"));
  }

  @Test
  public void ambiguousPlanRegeneratesFileStepsWithoutDroppingFiles() {
    CodePlanNormalizer normalizer =
        new CodePlanNormalizer(CodeValidationContract.builder().defaultRequiredEvidence("verify").build());
    Map<String, Object> normalized =
        normalizer.normalize(
            map(
                "goal", "多文件实现",
                "planned_files",
                Arrays.asList(
                    map("path", "a.txt", "action", "edit"),
                    map("path", "b.txt", "action", "edit"),
                    map("path", "c.txt", "action", "create")),
                "steps",
                Arrays.asList(
                    step("first", "第一部分", "implement", "search_replace"),
                    step("second", "第二部分", "implement", "search_replace"))));
    Set<String> expected = new LinkedHashSet<>();
    for (Object raw : (List<?>) normalized.get("planned_files")) {
      expected.add(String.valueOf(((Map<?, ?>) raw).get("file_id")));
    }
    Set<String> actual = new LinkedHashSet<>();
    for (Object raw : (List<?>) normalized.get("steps")) {
      Map<?, ?> step = (Map<?, ?>) raw;
      if (!"implement".equals(step.get("phase"))) continue;
      for (Object ref : (List<?>) step.get("file_refs")) actual.add(String.valueOf(ref));
    }
    assertEquals(expected, actual);
    assertTrue(((List<?>) normalized.get("steps")).size() <= 5);
  }

  @Test
  public void missingStepsPutDiscoverBeforeFileMappedImplementation() {
    CodePlanNormalizer normalizer =
        new CodePlanNormalizer(
            CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build());
    Map<String, Object> normalized =
        normalizer.normalize(
            map(
                "goal", "创建完整产品",
                "planned_files",
                Arrays.asList(
                    map("path", "index.html", "action", "create"),
                    map("path", "styles.css", "action", "create"))));
    List<?> steps = (List<?>) normalized.get("steps");
    assertEquals("discover", ((Map<?, ?>) steps.get(0)).get("phase"));
    assertEquals("implement", ((Map<?, ?>) steps.get(1)).get("phase"));
    assertEquals(2, ((List<?>) ((Map<?, ?>) steps.get(1)).get("file_refs")).size());
  }

  @Test
  public void interactionChecksNormalizeStringsObjectsAndDuplicateIds() {
    CodePlanNormalizer normalizer =
        new CodePlanNormalizer(CodeValidationContract.builder().build());
    Map<String, Object> normalized =
        normalizer.normalize(
            map(
                "goal", "交互页面",
                "interaction_checks",
                Arrays.asList(
                    "启动游戏后状态变化",
                    map("check_id", "restart", "description", "重新开始会重置分数"),
                    map("check_id", "restart", "action", "重新开始会重置棋盘"),
                    map("check_id", "非法 id", "expected", "方向按钮改变方向"))));
    List<?> checks = (List<?>) normalized.get("interaction_checks");
    assertEquals(4, checks.size());
    assertEquals("interaction-1", ((Map<?, ?>) checks.get(0)).get("check_id"));
    assertEquals("restart", ((Map<?, ?>) checks.get(1)).get("check_id"));
    assertEquals("restart-2", ((Map<?, ?>) checks.get(2)).get("check_id"));
    assertEquals("interaction-4", ((Map<?, ?>) checks.get(3)).get("check_id"));
    assertEquals("方向按钮改变方向", ((Map<?, ?>) checks.get(3)).get("description"));
  }

  @Test
  public void requiredInteractionGetsDefaultDynamicCheckWithoutRepairRound() {
    CodePlanNormalizer normalizer =
        new CodePlanNormalizer(CodeValidationContract.builder().build());
    for (Object invalid : Arrays.asList(null, "", Collections.singletonList(map("id", "missing")))) {
      Map<String, Object> normalized =
          normalizer.normalize(
              map(
                  "goal", "交互页面",
                  "interaction_required", true,
                  "interaction_checks", invalid));
      List<?> checks = (List<?>) normalized.get("interaction_checks");
      assertEquals(1, checks.size());
      assertEquals("interaction-required", ((Map<?, ?>) checks.get(0)).get("check_id"));
      assertTrue(
          String.valueOf(((Map<?, ?>) checks.get(0)).get("description"))
              .contains("操作前后"));
    }
  }

  @Test
  public void browserTransactionIsValidatedOnceBeforeQualityAndFinalize() {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .requireQualityReview("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put(CodeAgentToolNames.QUALITY_REVIEW, CodeToolRole.QUALITY);
    roles.put(CodeAgentToolNames.FINALIZE_TASK, CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(31L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    Map<String, Object> accepted = coordinator.acceptPlan(
        map(
            "goal", "验证游戏交互",
            "quality_mode", "interface_product",
            "interaction_required", true,
            "interaction_checks",
            Arrays.asList(
                map("check_id", "start-game", "description", "启动游戏"),
                map("check_id", "restart-game", "description", "重新开始")),
            "steps",
            Arrays.asList(
                step("discover", "读取", "discover", "read_file"),
                step("implement", "修改", "implement", "search_replace"),
                step("verify", "执行验证", "verify", "syntax_check", "browser_test"),
                step("quality", "质量审查", "quality", "quality_review"))));
    Map<?, ?> planState = (Map<?, ?>) accepted.get("plan_state");
    assertEquals(
        Arrays.asList("start-game", "restart-game"),
        planState.get("required_interaction_check_ids"));
    coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "content", "before")));
    ToolResult writeResult = coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    assertEquals("syntax_check", writeResult.data().get("recommended_next_action"));
    coordinator.recordAndDecorate(
        generation,
        "syntax_check",
        ToolArguments.empty(),
        ToolResult.success(map("passed", true)));

    AgentTool browser = tool("browser_test");
    ToolResult mismatch = coordinator.preflightResult(
        "browser_test",
        new ToolArguments(
            map(
                "goal", "验证交互",
                "scenarios",
                Arrays.asList(
                    dynamicScenario("start-game"),
                    dynamicScenario("extra"),
                    dynamicScenario("extra")))));
    assertTrue(mismatch.isSuccess());
    assertEquals(false, mismatch.data().get("passed"));
    assertEquals("test_plan_invalid", mismatch.data().get("failure_kind"));
    assertTrue(String.valueOf(mismatch.data().get("validation_issues"))
        .contains("missing_scenario_id"));
    assertTrue(String.valueOf(mismatch.data().get("validation_issues"))
        .contains("unexpected_scenario_id"));
    assertTrue(String.valueOf(mismatch.data().get("validation_issues"))
        .contains("duplicate_scenario_id"));
    assertTrue(mismatch.data().containsKey("test_retry_brief"));
    assertFalse(((Map<?, ?>) mismatch.data().get("test_retry_brief"))
        .containsKey("validation_issues"));
    assertEquals(0, mismatch.data().get("webview_launch_count"));

    ToolArguments browserArguments = new ToolArguments(
        map(
            "goal", "验证交互",
            "scenarios",
            Arrays.asList(
                dynamicScenario("start-game"),
                dynamicScenario("restart-game"))));
    ToolResult staticOnly = coordinator.preflightResult(
        "browser_test",
        new ToolArguments(
            map(
                "goal", "验证交互",
                "scenarios",
                Arrays.asList(
                    staticScenario("start-game"),
                    staticScenario("restart-game")))));
    assertTrue(staticOnly.isSuccess());
    assertEquals(false, staticOnly.data().get("passed"));
    assertEquals(0, staticOnly.data().get("webview_launch_count"));
    assertTrue(String.valueOf(staticOnly.data().get("failure_reason")).contains("actions"));
    assertNull(coordinator.preflightResult(
        "browser_test",
        new ToolArguments(map(
            "goal", "验证多阶段交互",
            "scenarios", Arrays.asList(
                checkpointScenario("start-game"),
                checkpointScenario("restart-game"))))));
    assertNull(coordinator.preflightResult("browser_test", browserArguments));

    ToolResult verified = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        browserArguments,
        passingBrowser("start-game", "restart-game"));
    assertTrue(verified.isSuccess());
    assertFalse(verified.data().containsKey("source_revision"));
    assertFalse(verified.data().containsKey("test_plan_hash"));
    assertFalse(verified.data().containsKey("covered_interaction_check_ids"));
    assertEquals(
        Arrays.asList("start-game", "restart-game"),
        ((Map<?, ?>) verified.data().get("coverage_summary")).get("passed_scenario_ids"));

    AgentTool finalize = tool(CodeAgentToolNames.FINALIZE_TASK);
    ToolArguments completed = new ToolArguments(map("status", "completed", "summary", "done"));
    assertFalse(coordinator.supports(new ToolInvocation(
        "blocked", finalize, new ToolArguments(map("status", "blocked", "summary", "blocked")))));
    assertFalse(coordinator.supports(new ToolInvocation(
        "needs-user",
        finalize,
        new ToolArguments(map("status", "needs_user_input", "summary", "question")))));
    ToolPolicyDecision beforeQuality = decision(
        coordinator, new ToolInvocation("finalize-before-quality", finalize, completed));
    assertEquals("finalize_precondition_failed", beforeQuality.result().errorCode());
    assertEquals("quality_review", beforeQuality.result().data().get("missing_stage"));

    AgentTool quality = tool(CodeAgentToolNames.QUALITY_REVIEW);
    ToolArguments qualityArguments = new ToolArguments(
        map(
            "passed", true,
            "blocking_gaps", Collections.emptyList(),
            "minimal_version_risk", false));
    assertNull(coordinator.preflightResult(CodeAgentToolNames.QUALITY_REVIEW, qualityArguments));
    assertFalse(qualityArguments.has("covered_interaction_check_ids"));
    coordinator.recordAndDecorate(
        generation,
        CodeAgentToolNames.QUALITY_REVIEW,
        qualityArguments,
        ToolResult.success(qualityArguments.asMap()));
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(coordinator, new ToolInvocation("finalize", finalize, completed)).kind());

    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    ToolResult browserBeforeSyntax = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        browserArguments,
        passingBrowser("start-game", "restart-game"));
    assertEquals("syntax_check", browserBeforeSyntax.data().get("recommended_next_action"));
    ToolPolicyDecision stale = decision(
        coordinator, new ToolInvocation("finalize-stale", finalize, completed));
    assertEquals("syntax_check", stale.result().data().get("missing_stage"));
  }

  @Test
  public void latestBrowserTransactionDoesNotUnionScenarioResults() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE,
            CodeValidationContract.builder().defaultRequiredEvidence("browser_test").build(),
            roles,
            workspace);
    coordinator.onRunStarted(new AgentRunContext(32L, "s", "workspace", "task"));
    coordinator.acceptPlan(
        map(
            "goal", "验证交互",
            "interaction_checks",
            Arrays.asList(
                map("check_id", "start-game", "description", "启动"),
                map("check_id", "restart-game", "description", "重开")),
            "steps", Collections.singletonList(
                step("verify", "验证", "verify", "browser_test"))));
    long generation = coordinator.generation();
    for (String id : Arrays.asList("start-game", "restart-game")) {
      ToolArguments arguments = new ToolArguments(
          map("scenarios", Collections.singletonList(
              map(
                  "id", id,
                  "actions", Collections.emptyList(),
                  "expectations", Collections.singletonList(
                      map("transition", "eventually_true"))))));
      coordinator.recordAndDecorate(
          generation,
          "browser_test",
          arguments,
          passingBrowser(id));
    }
    assertFalse(coordinator.hasCurrentEvidence("browser_test"));
    ToolPolicyDecision finalize = decision(
        coordinator,
        new ToolInvocation(
            "finalize",
            tool("finalize_task"),
            new ToolArguments(map("status", "completed", "summary", "done"))));
    assertEquals("browser_test", finalize.result().data().get("missing_stage"));
  }

  @Test
  public void productFailureRequiresSameSemanticPlanAndQualityUsesDerivedTrace() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder()
            .defaultRequiredEvidence("browser_test")
            .requireQualityReview("ui_product")
            .build(),
        roles,
        workspace);
    coordinator.onRunStarted(new AgentRunContext(321L, "s", "workspace", "task"));
    coordinator.acceptPlan(map(
        "goal", "验证页面",
        "quality_mode", "interface_product",
        "steps", Arrays.asList(
            step("verify", "验证", "verify", "browser_test"),
            step("quality", "审查", "quality", "quality_review"))));
    long generation = coordinator.generation();
    ToolArguments browserArguments = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));
    coordinator.recordAndDecorate(
        generation,
        "browser_test",
        browserArguments,
        ToolResult.success(map(
            "operation", "browser_test",
            "passed", false,
            "failure_kind", "product_code_failure",
            "source_revision", "source-1",
            "test_plan_hash", "plan-original",
            "test_retry_brief", map("issue", "派生阻断不应解锁测试语义"),
            "scenario_results", Collections.singletonList(map(
                "id", "smoke",
                "passed", false,
                "failures", Arrays.asList(
                    map(
                        "phase", "checkpoint",
                        "code", "checkpoint_timeout",
                        "failure_kind", "product_code_failure"),
                    map(
                        "phase", "expectation",
                        "code", "blocked_by_action",
                        "failure_kind", "test_expectation_mismatch")))))));
    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));

    ToolResult changed = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        browserArguments,
        passingBrowserWithHash("plan-changed", "smoke"));
    assertEquals(false, changed.data().get("passed"));
    assertEquals("test_plan_invalid", changed.data().get("failure_kind"));
    assertTrue(String.valueOf(changed.data().get("validation_issues"))
        .contains("regression_plan_changed"));

    ToolResult verified = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        browserArguments,
        passingBrowserWithHash("plan-original", "smoke"));
    assertEquals(true, verified.data().get("passed"));
    ToolArguments quality = new ToolArguments(map(
        "passed", true,
        "blocking_gaps", Collections.emptyList(),
        "claimed_but_unsupported", Collections.emptyList(),
        "minimal_version_risk", false,
        "web_evidence", Collections.singletonList("unsupported free text")));
    ToolResult reviewed = coordinator.recordAndDecorate(
        generation, "quality_review", quality, ToolResult.success(quality.asMap()));
    assertTrue(reviewed.data().containsKey("verified_behavior_evidence"));
    assertFalse(String.valueOf(reviewed.data().get("web_evidence"))
        .contains("unsupported free text"));
    assertTrue(String.valueOf(reviewed.data().get("web_evidence"))
        .contains("successful postconditions"));
    assertTrue(String.valueOf(reviewed.data().get("verified_behavior_evidence"))
        .contains("action_trace"));
    List<?> verifiedEvidence = (List<?>) reviewed.data().get("verified_behavior_evidence");
    assertFalse(((Map<?, ?>) verifiedEvidence.get(0)).containsKey("description"));

    ToolResult finalized = coordinator.recordAndDecorate(
        generation,
        "finalize_task",
        new ToolArguments(map("status", "completed")),
        ToolResult.success(map(
            "status", "completed",
            "verification", Collections.singletonList("键盘和触摸已验证"))));
    assertFalse(String.valueOf(finalized.data().get("verification")).contains("键盘"));
    assertTrue(String.valueOf(finalized.data().get("verification"))
        .contains("successful postconditions"));
  }

  @Test
  public void mixedBrowserFailureAllowsRepairAndDoesNotLockInvalidPlanHash() {
    CodeValidationContract contract = CodeValidationContract.builder()
        .defaultRequiredEvidence("syntax_check", "browser_test")
        .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put(CodeAgentToolNames.PLAN_TASK, CodeToolRole.PLAN);
    roles.put(CodeAgentToolNames.FINALIZE_TASK, CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    AgentRunContext run = new AgentRunContext(322L, "s", "workspace", "task");
    coordinator.onRunStarted(run);
    coordinator.acceptPlan(map(
        "goal", "修复混合故障",
        "planned_files", Collections.singletonList(map("path", "main.txt", "action", "edit")),
        "steps", Arrays.asList(
            step("implement", "修复", "implement", "search_replace"),
            step("verify", "验证", "verify", "syntax_check", "browser_test"))));
    long generation = coordinator.generation();
    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    coordinator.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));

    ToolArguments originalPlan = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));
    ToolResult mixed = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        originalPlan,
        ToolResult.success(map(
            "passed", false,
            "failure_kind", "product_code_failure",
            "recommended_next_action", "read_plan",
            "reading_brief", map("path", "main.txt"),
            "test_retry_brief", map("issue", "baseline already true"),
            "scenario_results", Collections.singletonList(map(
                "id", "smoke",
                "failures", Arrays.asList(
                    map("code", "page_error", "failure_kind", "product_code_failure"),
                    map("code", "baseline_already_true",
                        "failure_kind", "test_expectation_mismatch")))))));
    List<ToolSpec> registered = new ArrayList<>();
    for (String name : roles.keySet()) registered.add(tool(name).spec());
    AgentRoundContext round = new AgentRoundContext(run, 1);
    Set<String> mixedTools = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(mixedTools.contains("search_replace"));
    assertTrue(mixedTools.contains("browser_test"));
    assertEquals("read_plan", mixed.data().get("recommended_next_action"));

    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    Set<String> afterWrite = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(afterWrite.contains("syntax_check"));
    assertTrue(afterWrite.contains("search_replace"));
    assertFalse(afterWrite.contains("browser_test"));
    coordinator.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));

    ToolArguments correctedPlan = new ToolArguments(map(
        "goal", "修正已知 baseline",
        "scenarios", Collections.singletonList(map(
            "id", "smoke",
            "actions", Collections.emptyList(),
            "expectations", Collections.singletonList(map(
                "type", "selector_exists",
                "selector", "html",
                "transition", "eventually_true"))))));
    ToolResult passed = coordinator.recordAndDecorate(
        generation, "browser_test", correctedPlan, passingBrowser("smoke"));
    assertEquals(true, passed.data().get("passed"));
    assertEquals("none", passed.data().get("failure_kind"));
  }

  @Test
  public void changedRegressionPlanIsRejectedBeforeBrowserLaunch() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("browser_test", CodeToolRole.VERIFY);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder().defaultRequiredEvidence("browser_test").build(),
        roles,
        workspace);
    coordinator.onRunStarted(new AgentRunContext(322L, "s", "workspace", "task"));
    coordinator.acceptPlan(map(
        "goal", "验证页面",
        "steps", Collections.singletonList(
            step("verify", "验证", "verify", "browser_test"))));
    long generation = coordinator.generation();
    ToolArguments original = new ToolArguments(map(
        "goal", "验证页面",
        "scenarios", Collections.singletonList(staticScenario("smoke"))));
    String originalHash = BrowserTestContractValidator.validate(original.asMap())
        .executionPlanHash();
    coordinator.recordAndDecorate(
        generation,
        "browser_test",
        original,
        ToolResult.success(map(
            "operation", "browser_test",
            "passed", false,
            "failure_kind", "product_code_failure",
            "test_plan_hash", originalHash,
            "scenario_results", Collections.singletonList(map(
                "id", "smoke",
                "passed", false,
                "failures", Collections.singletonList(map(
                    "phase", "layout",
                    "code", "horizontal_overflow",
                    "failure_kind", "product_code_failure")))))));
    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));

    ToolArguments changed = new ToolArguments(map(
        "goal", "验证页面",
        "scenarios", Collections.singletonList(map(
            "id", "smoke",
            "actions", Collections.emptyList(),
            "expectations", Collections.singletonList(map(
                "type", "selector_exists", "selector", "#changed"))))));
    ToolResult rejected = coordinator.preflightResult("browser_test", changed);

    assertEquals(false, rejected.data().get("passed"));
    assertEquals("test_plan_invalid", rejected.data().get("failure_kind"));
    assertEquals(0, rejected.data().get("webview_launch_count"));
    assertTrue(String.valueOf(rejected.data().get("validation_issues"))
        .contains("regression_plan_changed"));
    assertNull(coordinator.preflightResult("browser_test", original));
  }

  @Test
  public void malformedPassedBrowserResultStaysInBrowserStage() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder().defaultRequiredEvidence("browser_test").build(),
        roles,
        workspace);
    AgentRunContext run = new AgentRunContext(34L, "s", "workspace", "task");
    coordinator.onRunStarted(run);
    coordinator.acceptPlan(
        map(
            "goal", "验证页面",
            "steps", Collections.singletonList(
                step("verify-browser", "浏览器验证", "verify", "browser_test"))));
    ToolArguments arguments = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));

    ToolResult malformed = coordinator.recordAndDecorate(
        coordinator.generation(),
        "browser_test",
        arguments,
        ToolResult.success(
            map(
                "passed", true,
                "failure_kind", "none",
                "source_revision", "source-1",
                "test_plan_hash", "plan-1",
                "coverage_summary",
                map(
                    "complete", true,
                    "passed_scenario_ids", Collections.singletonList("smoke"),
                    "failed_scenario_ids", Collections.emptyList()))));

    assertEquals(ToolResult.Status.SUCCESS, malformed.status());
    assertEquals(false, malformed.data().get("passed"));
    assertEquals("environment_failure", malformed.data().get("failure_kind"));
    assertFalse(coordinator.hasCurrentEvidence("browser_test"));
    List<ToolSpec> registered = Arrays.asList(
        tool("read_file").spec(),
        tool("read_plan").spec(),
        tool("browser_test").spec(),
        tool("finalize_task").spec());
    assertTrue(selectedNames(
        coordinator.selectTools(new AgentRoundContext(run, 1), registered)).contains("browser_test"));
  }

  @Test
  public void explicitBrowserToolStaysReachableWhenModelLabelsStepAsQuality() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .requireQualityReview("code_generation")
            .build(),
        roles,
        workspace);
    AgentRunContext run = new AgentRunContext(340L, "s", "workspace", "task");
    coordinator.onRunStarted(run);
    coordinator.acceptPlan(
        map(
            "goal", "验证页面",
            "quality_mode", "interface_product",
            "steps", Arrays.asList(
                step("verify-syntax", "语法验证", "verify", "syntax_check"),
                // Models sometimes describe the browser acceptance step as quality. Explicit
                // required_tools remains authoritative so the tool path cannot become hidden.
                step("browser-test", "浏览器验收", "quality", "browser_test"))));
    coordinator.recordAndDecorate(
        coordinator.generation(), "syntax_check", ToolResult.success(map("passed", true)));

    List<ToolSpec> registered = Arrays.asList(
        tool("read_file").spec(),
        tool("read_plan").spec(),
        tool("syntax_check").spec(),
        tool("browser_test").spec(),
        tool("quality_review").spec(),
        tool("finalize_task").spec());
    Set<String> beforeBrowser = selectedNames(coordinator.selectTools(
        new AgentRoundContext(run, 1), registered));
    assertTrue(beforeBrowser.contains("browser_test"));
    assertFalse(beforeBrowser.contains("quality_review"));

    ToolArguments browserArguments = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));
    coordinator.recordAndDecorate(
        coordinator.generation(), "browser_test", browserArguments, passingBrowser("smoke"));
    Set<String> afterBrowser = selectedNames(coordinator.selectTools(
        new AgentRoundContext(run, 2), registered));
    assertTrue(afterBrowser.contains("quality_review"));
    assertFalse(afterBrowser.contains("browser_test"));
  }

  @Test
  public void failedBrowserRetryKeepsOldTransactionButDoesNotAuthorizeCompletion() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder()
            .defaultRequiredEvidence("browser_test")
            .requireQualityReview("code_generation")
            .build(),
        roles,
        workspace);
    AgentRunContext run = new AgentRunContext(341L, "s", "workspace", "task");
    coordinator.onRunStarted(run);
    coordinator.acceptPlan(
        map(
            "goal", "验证页面",
            "steps", Arrays.asList(
                step("verify-browser", "浏览器验证", "verify", "browser_test"),
                step("quality", "质量", "quality", "quality_review"))));
    ToolArguments arguments = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));
    coordinator.recordAndDecorate(
        coordinator.generation(), "browser_test", arguments, passingBrowser("smoke"));
    assertTrue(coordinator.hasCurrentEvidence("browser_test"));
    coordinator.recordAndDecorate(
        coordinator.generation(), "quality_review", passingQuality());
    assertTrue(coordinator.completionEvidenceReady("code_generation"));

    ToolResult retry = coordinator.recordAndDecorate(
        coordinator.generation(),
        "browser_test",
        arguments,
        ToolResult.success(map("passed", true, "failure_kind", "none")));
    assertEquals(false, retry.data().get("passed"));
    assertTrue(coordinator.hasCurrentEvidence("browser_test"));
    assertFalse(coordinator.completionEvidenceReady("code_generation"));
    ToolResult qualityGap = coordinator.preflightResult(
        "quality_review",
        new ToolArguments(map("passed", true, "blocking_gaps", Collections.emptyList())));
    assertEquals(false, qualityGap.data().get("passed"));
    assertEquals("browser_test", qualityGap.data().get("recommended_next_action"));
    ToolPolicyDecision finalize = decision(
        coordinator,
        new ToolInvocation(
            "finalize",
            tool("finalize_task"),
            new ToolArguments(map("status", "completed", "summary", "done"))));
    assertEquals("browser_test", finalize.result().data().get("missing_stage"));
  }

  @Test
  public void currentQualityCanCompleteAdaptivePlanWithUnmappedProgressSteps() {
    CodeValidationContract contract = CodeValidationContract.builder()
        .defaultRequiredEvidence("syntax_check", "browser_test")
        .requireQualityReview("code_generation")
        .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(342L, "s", "workspace", "task"));
    coordinator.acceptPlan(
        map(
            "goal", "完成当前页面",
            "steps", Arrays.asList(
                step("discover", "历史映射未完成", "discover", "read_file"),
                step("implement", "历史映射未完成", "implement", "search_replace"),
                step("verify", "真实验证", "verify", "syntax_check", "browser_test"),
                step("quality", "真实质量", "quality", "quality_review"))));
    coordinator.recordAndDecorate(
        coordinator.generation(), "syntax_check", ToolResult.success(map("passed", true)));
    ToolArguments browserArgs = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));
    coordinator.recordAndDecorate(
        coordinator.generation(), "browser_test", browserArgs, passingBrowser("smoke"));
    List<ToolSpec> registered = Arrays.asList(
        tool("read_file").spec(),
        tool("search_replace").spec(),
        tool("syntax_check").spec(),
        tool("browser_test").spec(),
        tool("quality_review").spec(),
        tool("finalize_task").spec());
    assertTrue(selectedNames(coordinator.selectTools(
        new AgentRoundContext(
            new AgentRunContext(342L, "s", "workspace", "task"), 1), registered))
        .contains("quality_review"));
    coordinator.recordAndDecorate(
        coordinator.generation(), "quality_review", passingQuality());
    assertFalse(coordinator.isComplete());
    assertTrue(coordinator.completionEvidenceReady("code_generation"));
    ToolPolicyDecision finalize = decision(
        coordinator,
        new ToolInvocation(
            "finalize",
            tool("finalize_task"),
            new ToolArguments(
                map(
                    "status", "completed",
                    "completion_type", "code_generation",
                    "summary", "done"))));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, finalize.kind());
  }

  @Test
  public void explicitVerifyStepsAdvanceIndependently() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .build(),
        roles,
        workspace);
    coordinator.onRunStarted(new AgentRunContext(35L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "验证页面",
            "planned_files", Collections.singletonList(map("path", "main.txt", "action", "edit")),
            "steps",
            Arrays.asList(
                step("discover", "读取", "discover", "read_file"),
                step("implement", "修改", "implement", "search_replace"),
                step("verify-syntax", "语法", "verify", "syntax_check"),
                step("verify-browser", "浏览器", "verify", "browser_test"))));
    coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "content", "before")));
    ToolResult edited = coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    assertEquals("implement", currentStepId(edited.data().get("plan_state")));
    assertEquals(
        Collections.singletonList("verify:syntax_check"),
        ((Map<?, ?>) edited.data().get("plan_state")).get("missing_evidence"));
    ToolResult syntax = coordinator.recordAndDecorate(
        generation,
        "syntax_check",
        ToolArguments.empty(),
        ToolResult.success(map("passed", true)));
    assertEquals("verify-browser", currentStepId(syntax.data().get("plan_state")));
  }

  @Test
  public void explicitVerifyContractIsNotExpandedByDefaultEvidence() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .build(),
        roles,
        workspace);
    coordinator.onRunStarted(new AgentRunContext(351L, "s", "workspace", "task"));
    coordinator.acceptPlan(
        map(
            "goal", "只需语法验证",
            "steps", Collections.singletonList(
                step("verify-syntax", "语法", "verify", "syntax_check"))));
    coordinator.recordAndDecorate(
        coordinator.generation(), "syntax_check", ToolResult.success(map("passed", true)));

    assertTrue(coordinator.completionEvidenceReady("code_generation"));
    ToolPolicyDecision finalize = decision(
        coordinator,
        new ToolInvocation(
            "finalize",
            tool("finalize_task"),
            new ToolArguments(map("status", "completed", "summary", "done"))));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, finalize.kind());
  }

  @Test
  public void replanInvalidatesBrowserAndQualityInteractionEvidence() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    roles.put("finalize_task", CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE,
            CodeValidationContract.builder().defaultRequiredEvidence("browser_test").build(),
            roles,
            workspace);
    coordinator.onRunStarted(new AgentRunContext(33L, "s", "workspace", "task"));
    Map<String, Object> plan = map(
        "goal", "验证启动",
        "interaction_checks",
        Collections.singletonList(map("check_id", "start-game", "description", "启动")),
        "steps",
        Arrays.asList(
            step("verify", "验证", "verify", "browser_test"),
            step("quality", "审查", "quality", "quality_review")));
    coordinator.acceptPlan(plan);
    long generation = coordinator.generation();
    coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "content", "before")));
    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    ToolArguments browserArguments = new ToolArguments(
        map("scenarios", Collections.singletonList(map("id", "start-game"))));
    coordinator.recordAndDecorate(
        generation,
        "browser_test",
        browserArguments,
        passingBrowser("start-game"));
    ToolArguments injected = new ToolArguments(
        map(
            "passed", true,
            "minimal_version_risk", false,
            "blocking_gaps", Collections.emptyList()));
    assertNull(coordinator.preflightResult("quality_review", injected));
    coordinator.recordAndDecorate(
        generation,
        "quality_review",
        injected,
        ToolResult.success(injected.asMap()));
    ToolArguments completed = new ToolArguments(map("status", "completed", "summary", "done"));
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(
            coordinator,
            new ToolInvocation("finalize-before-replan", tool("finalize_task"), completed)).kind());

    Map<String, Object> replanned = new LinkedHashMap<>(plan);
    replanned.put("replan_reason", "调整后重新确认");
    coordinator.acceptPlan(replanned);
    ToolPolicyDecision stale = decision(
        coordinator,
        new ToolInvocation("finalize-after-replan", tool("finalize_task"), completed));
    assertEquals("finalize_precondition_failed", stale.result().errorCode());
    assertEquals("browser_test", stale.result().data().get("missing_stage"));
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
  public void fullOrdinaryReadAuthorizesEditAndCreateDoesNotNeedRead() throws Exception {
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
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, new ToolInvocation("e-path-only", edit, path)).kind());
    policy.onToolCompleted(
        first,
        new ToolInvocation("r-content", read, path),
        ToolResult.success(map("path", "main.txt", "content", "before")));
    assertEquals(
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, new ToolInvocation("e-unproven-full", edit, path)).kind());
    policy.onToolCompleted(
        first,
        new ToolInvocation("r-full", read, path),
        ToolResult.success(
            map("path", "main.txt", "content", "before", "full_file", true)));
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, new ToolInvocation("e2", edit, path)).kind());

    policy.onRunStarted(new AgentRunContext(2L, "s", "workspace", "next"));
    assertEquals(
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, new ToolInvocation("e3", edit, path)).kind());
  }

  @Test
  public void batchWithoutDirectContentDoesNotAuthorizeButFullOrdinaryReadDoes() throws Exception {
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
                    map("result", map("path", "index.html", "content", "index.html")),
                    map("result", map("resolved_path", "style.css", "content", "style.css"))),
                "truncated",
                true)));

    assertEquals(
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, editInvocation("index.html", edit)).kind());
    assertEquals(
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, editInvocation("style.css", edit)).kind());
    ToolPolicyDecision missing = decision(policy, editInvocation("game.js", edit));
    assertEquals(ToolPolicyDecision.Kind.ERROR, missing.kind());
    assertEquals("read_evidence_required", missing.result().errorCode());
    assertTrue(missing.result().message().contains("当前 revision"));

    policy.onToolCompleted(
        run,
        new ToolInvocation(
            "read-game",
            read,
            new ToolArguments(Collections.singletonMap("path", "game.js"))),
        ToolResult.success(
            map("path", "game.js", "content", "game.js", "full_file", true)));
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, editInvocation("game.js", edit)).kind());
  }

  @Test
  public void successfulWriteEvidenceIsNotDowngradedByLaterSnippet() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentRunContext run = new AgentRunContext(19L, "s", "workspace", "task");
    AgentTool read = tool("read_file");
    AgentTool edit = tool("search_replace");
    ToolInvocation editMain = editInvocation("main.txt", edit);
    policy.onRunStarted(run);

    policy.onToolCompleted(
        run,
        editMain,
        ToolResult.success(map("path", "main.txt", "changed", true)));
    policy.onToolCompleted(
        run,
        new ToolInvocation(
            "snippet",
            read,
            new ToolArguments(map("path", "main.txt", "start_line", 1, "end_line", 1))),
        ToolResult.success(
            map("path", "main.txt", "content", "before", "truncated", true)));

    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, editMain).kind());
  }

  @Test
  public void sameRevisionSnippetsMergeAcrossBatchReplacementAnchors() throws Exception {
    Files.write(
        new File(root, "main.txt").toPath(),
        "alpha\nmiddle\nomega".getBytes(StandardCharsets.UTF_8));
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentRunContext run = new AgentRunContext(20L, "s", "workspace", "task");
    AgentTool read = tool("read_file");
    AgentTool edit = tool("search_replace");
    policy.onRunStarted(run);
    String revision = sha256(new File(root, "main.txt"));

    policy.onToolCompleted(
        run,
        new ToolInvocation(
            "snippet-a",
            read,
            new ToolArguments(map("path", "main.txt", "start_line", 1, "end_line", 1))),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", revision,
                "content", "alpha",
                "truncated", true)));
    policy.onToolCompleted(
        run,
        new ToolInvocation(
            "snippet-b",
            read,
            new ToolArguments(map("path", "main.txt", "start_line", 3, "end_line", 3))),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", revision,
                "content", "omega",
                "truncated", true)));

    ToolInvocation batchEdit =
        new ToolInvocation(
            "batch",
            edit,
            new ToolArguments(
                map(
                    "path", "main.txt",
                    "replacements",
                    Arrays.asList(
                        map("old", "alpha", "new", "first"),
                        map("old", "omega", "new", "last")))));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, batchEdit).kind());

    ToolInvocation invented =
        new ToolInvocation(
            "invented",
            edit,
            new ToolArguments(
                map(
                    "path", "main.txt",
                    "replacements",
                    Arrays.asList(
                        map("old", "alpha", "new", "first"),
                        map("old", "not-read", "new", "last")))));
    assertEquals(ToolPolicyDecision.Kind.ERROR, decision(policy, invented).kind());
  }

  @Test
  public void searchReplaceBypassesReadGateWhileRewriteRemainsGuarded() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("rewrite", CodeToolRole.EDIT);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    ReadBeforeEditPolicy legacy = new ReadBeforeEditPolicy(workspace, roles, "legacy");
    ToolInvocation search = editInvocation("main.txt", tool("search_replace"));
    ToolInvocation rewrite = editInvocation("main.txt", tool("rewrite"));

    assertFalse(policy.supports(search));
    assertTrue(policy.supports(rewrite));
    assertTrue(legacy.supports(search));
    assertTrue(legacy.supports(rewrite));
    assertEquals(ToolPolicyDecision.Kind.ERROR, decision(policy, rewrite).kind());
  }

  @Test
  public void presetSnapshotsAtomicEditGateModeAtConstruction() {
    CodeAgentPreset rewriteOnly =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .languageTools(Arrays.asList(tool("search_replace"), tool("rewrite")))
            .build();
    CodeAgentPreset legacy =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .languageTools(Arrays.asList(tool("search_replace"), tool("rewrite")))
            .atomicEditReadGate("legacy")
            .build();
    ToolInvocation search = editInvocation("main.txt", tool("search_replace"));
    ToolInvocation rewrite = editInvocation("main.txt", tool("rewrite"));
    ReadBeforeEditPolicy rewriteOnlyPolicy =
        (ReadBeforeEditPolicy) rewriteOnly.toolPolicies().get(1);
    ReadBeforeEditPolicy legacyPolicy =
        (ReadBeforeEditPolicy) legacy.toolPolicies().get(1);

    assertFalse(rewriteOnlyPolicy.supports(search));
    assertTrue(rewriteOnlyPolicy.supports(rewrite));
    assertTrue(legacyPolicy.supports(search));
    assertTrue(legacyPolicy.supports(rewrite));
  }

  @Test
  public void successfulWriteRefreshesReadEvidenceAndFailedOrUnchangedWriteDoesNot()
      throws Exception {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("create_file", CodeToolRole.CREATE);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentRunContext run = new AgentRunContext(9L, "s", "workspace", "task");
    AgentTool read = tool("read_plan");
    AgentTool edit = tool("search_replace");
    ToolInvocation readMain =
        new ToolInvocation(
            "read", read, new ToolArguments(Collections.singletonMap("path", "main.txt")));
    ToolInvocation editMain = editInvocation("main.txt", edit);
    policy.onRunStarted(run);
    policy.onToolCompleted(
        run,
        readMain,
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "coverage_summary", map("ready_for_edit", true),
                "evidence", Collections.singletonList(map("evidence_id", "ev", "content", "before")))));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, editMain).kind());

    policy.onToolCompleted(
        run,
        editMain,
        ToolResult.success(map("resolved_path", "main.txt", "changed", true)));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, editMain).kind());

    policy.onToolCompleted(
        run,
        readMain,
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "coverage_summary", map("ready_for_edit", true),
                "evidence", Collections.singletonList(map("evidence_id", "ev2", "content", "after")))));
    policy.onToolCompleted(
        run,
        editMain,
        ToolResult.success(map("path", "main.txt", "changed", false)));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, editMain).kind());

    policy.onToolCompleted(
        run,
        editMain,
        ToolResult.error("write_failed", "failed", true, map("path", "main.txt")));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, editMain).kind());

    policy.onToolCompleted(
        run,
        editMain,
        ToolResult.error(
            "partial_write",
            "partially applied",
            true,
            map("path", "main.txt", "partial_apply", true, "applied_count", 1)));
    assertEquals(ToolPolicyDecision.Kind.ERROR, decision(policy, editMain).kind());
  }

  @Test
  public void explicitReadPathsWithoutContentDoNotAuthorizeEdits() throws Exception {
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
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, editInvocation("style.css", tool("search_replace"))).kind());
  }

  @Test
  public void readyReadPlanMustMatchCurrentFileRevisionAndInvalidatesOnChange() throws Exception {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentRunContext run = new AgentRunContext(18L, "s", "workspace", "task");
    AgentTool readPlan = tool("read_plan");
    AgentTool edit = tool("search_replace");
    ToolArguments path = new ToolArguments(Collections.singletonMap("path", "main.txt"));
    policy.onRunStarted(run);

    Map<String, Object> base = map(
        "path", "main.txt",
        "revision", sha256(new File(root, "main.txt")),
        "evidence", Collections.singletonList(map("evidence_id", "ev_main", "content", "before")));
    Map<String, Object> incomplete = new LinkedHashMap<>(base);
    incomplete.put("coverage_summary", map("ready_for_edit", false));
    policy.onToolCompleted(
        run, new ToolInvocation("plan-incomplete", readPlan, path), ToolResult.success(incomplete));
    assertEquals(ToolPolicyDecision.Kind.ERROR, decision(policy, editInvocation("main.txt", edit)).kind());

    Map<String, Object> stale = new LinkedHashMap<>(base);
    stale.put("revision", String.join("", Collections.nCopies(64, "0")));
    stale.put("coverage_summary", map("ready_for_edit", true));
    policy.onToolCompleted(
        run, new ToolInvocation("plan-stale", readPlan, path), ToolResult.success(stale));
    assertEquals(ToolPolicyDecision.Kind.ERROR, decision(policy, editInvocation("main.txt", edit)).kind());

    Map<String, Object> ready = new LinkedHashMap<>(base);
    ready.put("coverage_summary", map("ready_for_edit", true));
    policy.onToolCompleted(
        run, new ToolInvocation("plan-ready", readPlan, path), ToolResult.success(ready));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, editInvocation("main.txt", edit)).kind());

    Files.write(new File(root, "main.txt").toPath(), "changed".getBytes(StandardCharsets.UTF_8));
    assertEquals(ToolPolicyDecision.Kind.ERROR, decision(policy, editInvocation("main.txt", edit)).kind());
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
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
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
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "content", "source")));
    assertEquals("implement", currentStepId(read.data().get("plan_state")));
    ToolResult duplicateRead = coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "content", "source")));
    assertFalse(duplicateRead.data().containsKey("plan_state"));

    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", false)));
    assertFalse(coordinator.isComplete());
    ToolResult edited = coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    assertEquals("implement", currentStepId(edited.data().get("plan_state")));
    assertEquals(
        Collections.singletonList("verify:syntax_check"),
        ((Map<?, ?>) edited.data().get("plan_state")).get("missing_evidence"));

    ToolResult syntaxResult = coordinator.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));
    assertEquals("browser_test", syntaxResult.data().get("recommended_next_action"));
    coordinator.recordAndDecorate(
        generation,
        "browser_test",
        new ToolArguments(map("scenarios", Collections.singletonList(staticScenario("smoke")))),
        ToolResult.success(map("passed", false, "failure_kind", "test_expectation_mismatch")));
    assertFalse(coordinator.isComplete());
    ToolResult verified = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        new ToolArguments(map("scenarios", Collections.singletonList(staticScenario("smoke")))),
        passingBrowser("smoke"));
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
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    assertFalse(coordinator.isComplete());
    assertEquals("implement", currentStepId(changedAgain.data().get("plan_state")));

    coordinator.onRunStarted(new AgentRunContext(12L, "session", "workspace", "next"));
    ToolResult late = coordinator.recordAndDecorate(
        generation, "browser_test", ToolResult.success(map("passed", true)));
    assertFalse(late.data().containsKey("plan_state"));
    assertFalse(coordinator.hasPlan());
  }

  @Test
  public void managedPlanAcceptsOnlyReadyGoalDrivenReadCoverage() throws Exception {
    CodeValidationContract contract =
        CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(19L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "repair resize",
            "steps",
            Arrays.asList(
                step("discover", "collect evidence", "discover", "read_plan"),
                step("implement", "apply edit", "implement", "search_replace"),
                step("verify", "verify", "verify", "syntax_check"))));
    ToolArguments arguments = new ToolArguments(map("path", "main.txt"));
    Map<String, Object> readData =
        map(
            "path", "main.txt",
            "revision", sha256(new File(root, "main.txt")),
            "read_paths", Collections.singletonList("main.txt"),
            "evidence", Collections.singletonList(map("evidence_id", "ev_main", "content", "before")));
    Map<String, Object> incomplete = new LinkedHashMap<>(readData);
    incomplete.put("coverage_summary", map("ready_for_edit", false));
    ToolResult ignored = coordinator.recordAndDecorate(
        generation, "read_plan", arguments, ToolResult.success(incomplete));
    assertFalse(ignored.data().containsKey("plan_state"));

    Map<String, Object> ready = new LinkedHashMap<>(readData);
    ready.put("coverage_summary", map("ready_for_edit", true));
    ToolResult accepted = coordinator.recordAndDecorate(
        generation, "read_plan", arguments, ToolResult.success(ready));
    assertEquals("implement", currentStepId(accepted.data().get("plan_state")));
  }

  @Test
  public void dynamicToolSurfaceRoutesReadEditVerifyAndBrowserFailures() throws Exception {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .requireQualityReview("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("list_dir", CodeToolRole.DISCOVER);
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_file_batch", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("create_file", CodeToolRole.CREATE);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("rewrite", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put(CodeAgentToolNames.PLAN_TASK, CodeToolRole.PLAN);
    roles.put(CodeAgentToolNames.QUALITY_REVIEW, CodeToolRole.QUALITY);
    roles.put(CodeAgentToolNames.FINALIZE_TASK, CodeToolRole.FINALIZE);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    AgentRunContext run = new AgentRunContext(90L, "s", "workspace", "task");
    AgentRoundContext round = new AgentRoundContext(run, 0);
    List<ToolSpec> registered = new ArrayList<>();
    for (String name : roles.keySet()) registered.add(tool(name).spec());
    coordinator.onRunStarted(run);

    assertEquals(
        new LinkedHashSet<>(Arrays.asList("list_dir", "read_file", "read_plan", "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));
    assertTrue(selectedNames(coordinator.selectTools(round, registered)).contains("read_file"));
    assertFalse(selectedNames(coordinator.selectTools(round, registered)).contains("read_file_batch"));
    ToolInvocation hiddenBatch =
        new ToolInvocation(
            "hidden-batch",
            tool("read_file_batch"),
            new ToolArguments(map("path", root.getAbsolutePath())));
    assertFalse(coordinator.supports(hiddenBatch));

    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "修复交互",
            "quality_mode", "ui_product",
            "planned_files", Collections.singletonList(map("path", "main.txt", "action", "edit")),
            "steps",
            Arrays.asList(
                step("discover", "收集证据", "discover", "read_plan"),
                step("implement", "修复", "implement", "search_replace"),
                step("verify", "验证", "verify", "syntax_check", "browser_test"),
                step("quality", "审查", "quality", "quality_review"))));
    Map<String, Object> ready =
        map(
            "path", "main.txt",
            "revision", sha256(new File(root, "main.txt")),
            "coverage_summary", map("ready_for_edit", true),
            "evidence", Collections.singletonList(map("evidence_id", "ev", "content", "before")));
    coordinator.recordAndDecorate(
        generation,
        "read_plan",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(ready));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));

    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "syntax_check",
            "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));
    coordinator.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "browser_test",
            "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));

    ToolResult invalidBrowser = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        ToolResult.success(map("passed", false, "failure_kind", "test_plan_invalid")));
    assertEquals("browser_test", invalidBrowser.data().get("recommended_next_action"));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "browser_test",
            "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));
    assertTrue(selectedNames(coordinator.selectTools(round, registered))
        .contains("search_replace"));
    coordinator.recordAndDecorate(
        generation,
        "browser_test",
        ToolResult.success(map("passed", false, "failure_kind", "environment_failure")));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "browser_test",
            "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));
    ToolResult mixedFailure = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        ToolResult.success(map(
            "passed", false,
            "failure_kind", "product_code_failure",
            "recommended_next_action", "read_plan",
            "reading_brief", map("path", "main.txt"),
            "test_retry_brief", map("issue", "baseline already true"),
            "scenario_results", Collections.singletonList(map(
                "id", "interaction",
                "failures", Arrays.asList(
                    map(
                        "code", "page_error",
                        "failure_kind", "product_code_failure"),
                    map(
                        "code", "baseline_already_true",
                        "failure_kind", "test_expectation_mismatch")))))));
    assertEquals("read_plan", mixedFailure.data().get("recommended_next_action"));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "browser_test",
            "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));
    ToolResult productFailure = coordinator.recordAndDecorate(
        generation,
        "browser_test",
        ToolResult.success(map("passed", false, "failure_kind", "product_code_failure")));
    assertEquals("read_plan", productFailure.data().get("recommended_next_action"));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));

    coordinator.recordAndDecorate(
        generation,
        "read_plan",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(ready));
    assertTrue(selectedNames(coordinator.selectTools(round, registered)).contains("search_replace"));
    assertTrue(selectedNames(coordinator.selectTools(round, registered)).contains("read_plan"));

    ToolResult retryFailure = coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt", "old", "stale", "new", "after")),
        ToolResult.error(
            "file_tool_error", "search_match_count:0:expected=1:actual=0", false));
    assertEquals("search_replace", retryFailure.data().get("recommended_next_action"));
    assertEquals(
        new LinkedHashSet<>(Arrays.asList(
            "read_file", "read_plan", "search_replace", "plan_task", "finalize_task")),
        selectedNames(coordinator.selectTools(round, registered)));
    AgentTool recoveryRead = tool("read_file");
    assertFalse(coordinator.supports(new ToolInvocation(
        "full-read", recoveryRead, new ToolArguments(map("path", "main.txt")))));
    coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt", "start_line", 1, "end_line", 1)),
        ToolResult.success(map("path", "main.txt", "content", "before")));
    assertTrue(selectedNames(coordinator.selectTools(round, registered)).contains("search_replace"));
    assertTrue(selectedNames(coordinator.selectTools(round, registered)).contains("read_file"));
  }

  @Test
  public void plannedSearchReplaceRemainsVisibleAcrossVerificationAndQuality() throws Exception {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check")
            .requireQualityReview("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("create_file", CodeToolRole.CREATE);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("rewrite", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put(CodeAgentToolNames.QUALITY_REVIEW, CodeToolRole.QUALITY);
    roles.put(CodeAgentToolNames.PLAN_TASK, CodeToolRole.PLAN);
    roles.put(CodeAgentToolNames.FINALIZE_TASK, CodeToolRole.FINALIZE);
    List<ToolSpec> registered = new ArrayList<>();
    for (String name : roles.keySet()) registered.add(tool(name).spec());
    AgentRunContext run = new AgentRunContext(91L, "s", "workspace", "task");
    AgentRoundContext round = new AgentRoundContext(run, 0);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE, contract, roles, workspace, true);
    coordinator.onRunStarted(run);
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "修复并验证",
            "quality_mode", "ui_product",
            "steps",
            Arrays.asList(
                step("discover", "读取", "discover", "read_file"),
                step("implement", "修改", "implement", "search_replace"),
                step("verify", "验证", "verify", "syntax_check"),
                step("quality", "审查", "quality", "quality_review"))));
    coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "content", "before")));
    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));

    Set<String> duringVerify = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(duringVerify.contains("search_replace"));
    assertTrue(duringVerify.contains("syntax_check"));
    assertFalse(duringVerify.contains("create_file"));
    assertFalse(duringVerify.contains("rewrite"));

    coordinator.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));
    Set<String> duringQuality = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(duringQuality.contains("search_replace"));
    assertTrue(duringQuality.contains(CodeAgentToolNames.QUALITY_REVIEW));
    assertFalse(duringQuality.contains("create_file"));
    assertFalse(duringQuality.contains("rewrite"));

    coordinator.recordAndDecorate(
        generation,
        CodeAgentToolNames.QUALITY_REVIEW,
        ToolResult.success(map(
            "passed", false,
            "blocking_gaps", Collections.singletonList("需要修复视觉问题"),
            "minimal_version_risk", false)));
    Set<String> afterQualityFailure = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(afterQualityFailure.contains("search_replace"));
    assertFalse(afterQualityFailure.contains(CodeAgentToolNames.QUALITY_REVIEW));

    ManagedCodePlanCoordinator legacy =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE, contract, roles, workspace, false);
    legacy.onRunStarted(new AgentRunContext(92L, "s", "workspace", "task"));
    long legacyGeneration = legacy.generation();
    legacy.recordAndDecorate(
        legacyGeneration,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "content", "before")));
    legacy.recordAndDecorate(
        legacyGeneration,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    assertFalse(selectedNames(legacy.selectTools(round, registered)).contains("search_replace"));
    assertTrue(selectedNames(legacy.selectTools(round, registered)).contains("syntax_check"));

    ManagedCodePlanCoordinator readOnlyVerify =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE, contract, roles, workspace, true);
    readOnlyVerify.onRunStarted(new AgentRunContext(93L, "s", "workspace", "task"));
    long readOnlyGeneration = readOnlyVerify.generation();
    readOnlyVerify.acceptPlan(
        map(
            "goal", "先验证再按证据修复",
            "quality_mode", "interface_product",
            "steps", Collections.singletonList(
                step("verify", "验证", "verify", "syntax_check"))));
    readOnlyVerify.recordAndDecorate(
        readOnlyGeneration,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "content", "before")));
    Set<String> readOnlyVerifyTools =
        selectedNames(readOnlyVerify.selectTools(round, registered));
    assertFalse(readOnlyVerifyTools.contains("search_replace"));
    assertTrue(readOnlyVerifyTools.contains("syntax_check"));
    assertFalse(readOnlyVerifyTools.contains("create_file"));
    assertFalse(readOnlyVerifyTools.contains("rewrite"));
    readOnlyVerify.recordAndDecorate(
        readOnlyGeneration, "syntax_check", ToolResult.success(map("passed", true)));
    Set<String> readOnlyQualityTools =
        selectedNames(readOnlyVerify.selectTools(round, registered));
    assertFalse(readOnlyQualityTools.contains("search_replace"));
    assertTrue(readOnlyQualityTools.contains(CodeAgentToolNames.QUALITY_REVIEW));
  }

  @Test
  public void repeatedEditsRemainReachableAndReplanRequiresFreshWriteEvidence() throws Exception {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .requireQualityReview("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("create_file", CodeToolRole.CREATE);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("rewrite", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put(CodeAgentToolNames.QUALITY_REVIEW, CodeToolRole.QUALITY);
    roles.put(CodeAgentToolNames.PLAN_TASK, CodeToolRole.PLAN);
    roles.put(CodeAgentToolNames.FINALIZE_TASK, CodeToolRole.FINALIZE);
    List<ToolSpec> registered = new ArrayList<>();
    for (String name : roles.keySet()) registered.add(tool(name).spec());
    AgentRunContext run = new AgentRunContext(194L, "s", "workspace", "task");
    AgentRoundContext round = new AgentRoundContext(run, 0);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE, contract, roles, workspace, false);
    coordinator.onRunStarted(run);
    long generation = coordinator.generation();
    Map<String, Object> plan = map(
        "goal", "分批完成性能优化",
        "quality_mode", "ui_product",
        "planned_files", Collections.singletonList(map("path", "main.txt", "action", "edit")),
        "steps", Arrays.asList(
            step("discover", "读取", "discover", "read_file"),
            step("implement", "完成全部编辑", "implement", "search_replace"),
            step("verify", "验证", "verify", "syntax_check", "browser_test"),
            step("quality", "审查", "quality", "quality_review")));
    coordinator.acceptPlan(plan);
    coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "content", "before")));

    ToolArguments mainPath = new ToolArguments(map("path", "main.txt"));
    ToolResult firstWrite = coordinator.recordAndDecorate(
        generation,
        "search_replace",
        mainPath,
        ToolResult.success(map("path", "main.txt", "changed", true)));
    assertEquals("implement", currentStepId(firstWrite.data().get("plan_state")));
    Set<String> afterFirst = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(afterFirst.contains("search_replace"));
    assertTrue(afterFirst.contains("syntax_check"));
    assertFalse(afterFirst.contains("create_file"));
    assertFalse(afterFirst.contains("rewrite"));

    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        mainPath,
        ToolResult.success(map("path", "main.txt", "changed", true)));
    Set<String> afterSecond = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(afterSecond.contains("search_replace"));
    assertTrue(afterSecond.contains("syntax_check"));
    coordinator.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));
    Set<String> afterSyntax = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(afterSyntax.contains("search_replace"));
    assertTrue(afterSyntax.contains("browser_test"));

    ToolArguments browserArgs = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));
    coordinator.recordAndDecorate(
        generation, "browser_test", browserArgs, passingBrowser("smoke"));
    Set<String> afterBrowser = selectedNames(coordinator.selectTools(round, registered));
    assertTrue(afterBrowser.contains("search_replace"));
    assertTrue(afterBrowser.contains(CodeAgentToolNames.QUALITY_REVIEW));

    Map<String, Object> replanned = coordinator.acceptPlan(map(
        "goal", "完成剩余优化",
        "replan_reason", "仍有计划内编辑未完成",
        "quality_mode", "ui_product",
        "planned_files", Collections.singletonList(map("path", "main.txt", "action", "edit")),
        "steps", Arrays.asList(
            step("remaining", "完成剩余编辑", "implement", "search_replace"),
            step("reverify", "重新验证", "verify", "syntax_check", "browser_test"),
            step("requality", "重新审查", "quality", "quality_review"))));
    assertEquals("remaining", currentStepId(replanned.get("plan_state")));
    assertFalse(((List<?>) ((Map<?, ?>) replanned.get("plan_state")).get("done_steps"))
        .contains("remaining"));
    assertTrue(selectedNames(coordinator.selectTools(round, registered))
        .contains("search_replace"));
  }

  @Test
  public void planRemainsReachableAfterReadWriteVerifyAndFailure() throws Exception {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("list_dir", CodeToolRole.DISCOVER);
    roles.put("read_file", CodeToolRole.READ);
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put(CodeAgentToolNames.PLAN_TASK, CodeToolRole.PLAN);
    roles.put(CodeAgentToolNames.QUALITY_REVIEW, CodeToolRole.QUALITY);
    roles.put(CodeAgentToolNames.FINALIZE_TASK, CodeToolRole.FINALIZE);
    List<ToolSpec> registered = new ArrayList<>();
    for (String name : roles.keySet()) registered.add(tool(name).spec());
    AgentRunContext run = new AgentRunContext(190L, "s", "workspace", "task");
    AgentRoundContext round = new AgentRoundContext(run, 0);
    ManagedCodePlanCoordinator adaptive =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    adaptive.onRunStarted(run);
    long generation = adaptive.generation();
    adaptive.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "content", "before")));
    Set<String> afterRead = selectedNames(adaptive.selectTools(round, registered));
    assertTrue(afterRead.contains(CodeAgentToolNames.PLAN_TASK));
    assertTrue(afterRead.contains("search_replace"));

    adaptive.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true)));
    Set<String> afterWrite = selectedNames(adaptive.selectTools(round, registered));
    assertTrue(afterWrite.contains(CodeAgentToolNames.PLAN_TASK));
    assertFalse(afterWrite.contains("search_replace"));
    assertTrue(afterWrite.contains("syntax_check"));

    adaptive.acceptPlan(
        map(
            "goal", "验证",
            "steps",
            Arrays.asList(
                step("verify", "验证", "verify", "syntax_check", "browser_test"),
                step("quality", "审查", "quality", "quality_review"))));
    assertTrue(selectedNames(adaptive.selectTools(round, registered))
        .contains(CodeAgentToolNames.PLAN_TASK));
    adaptive.recordAndDecorate(
        generation, "syntax_check", ToolResult.success(map("passed", true)));
    assertTrue(selectedNames(adaptive.selectTools(round, registered))
        .contains(CodeAgentToolNames.PLAN_TASK));
    adaptive.recordAndDecorate(
        generation,
        "browser_test",
        ToolResult.success(map("passed", false, "failure_kind", "test_plan_invalid")));
    Set<String> afterFailure = selectedNames(adaptive.selectTools(round, registered));
    assertTrue(afterFailure.contains(CodeAgentToolNames.PLAN_TASK));
    assertTrue(afterFailure.contains("browser_test"));

    ManagedCodePlanCoordinator force =
        new ManagedCodePlanCoordinator(CodePlanningMode.FORCE, contract, roles, workspace);
    force.onRunStarted(new AgentRunContext(191L, "s", "workspace", "task"));
    force.recordAndDecorate(
        force.generation(),
        "read_file",
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "content", "before")));
    Set<String> forceTools = selectedNames(force.selectTools(round, registered));
    assertTrue(forceTools.contains(CodeAgentToolNames.PLAN_TASK));
    assertFalse(forceTools.contains("search_replace"));
  }

  @Test
  public void successfulBrowserResultCanDeriveOptionalMetadata() {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("browser_test", CodeToolRole.VERIFY);
    ManagedCodePlanCoordinator coordinator = new ManagedCodePlanCoordinator(
        CodePlanningMode.ADAPTIVE,
        CodeValidationContract.builder().defaultRequiredEvidence("browser_test").build(),
        roles,
        workspace);
    coordinator.onRunStarted(new AgentRunContext(192L, "s", "workspace", "task"));
    coordinator.acceptPlan(
        map(
            "goal", "浏览器验证",
            "steps", Collections.singletonList(
                step("verify", "验证", "verify", "browser_test"))));
    ToolArguments arguments = new ToolArguments(
        map("scenarios", Collections.singletonList(staticScenario("smoke"))));
    ToolResult result = coordinator.recordAndDecorate(
        coordinator.generation(),
        "browser_test",
        arguments,
        ToolResult.success(
            map(
                "passed", true,
                "scenario_results",
                Collections.singletonList(map("id", "smoke", "passed", true)))));
    assertTrue(result.isSuccess());
    assertEquals(true, result.data().get("passed"));
    assertEquals("none", result.data().get("failure_kind"));
    assertEquals(true, ((Map<?, ?>) result.data().get("coverage_summary")).get("complete"));
    assertTrue(coordinator.hasCurrentEvidence("browser_test"));
  }

  @Test
  public void nonMutatingSearchReplaceFailurePreservesCurrentEvidence() throws Exception {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_plan", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentRunContext run = new AgentRunContext(91L, "s", "workspace", "task");
    AgentTool readPlan = tool("read_plan");
    AgentTool edit = tool("search_replace");
    policy.onRunStarted(run);
    policy.onToolCompleted(
        run,
        new ToolInvocation(
            "plan", readPlan, new ToolArguments(Collections.singletonMap("path", "main.txt"))),
        ToolResult.success(
            map(
                "path", "main.txt",
                "revision", sha256(new File(root, "main.txt")),
                "coverage_summary", map("ready_for_edit", true),
                "evidence", Collections.singletonList(map("evidence_id", "ev", "content", "before")))));
    ToolInvocation failedEdit =
        new ToolInvocation(
            "failed",
            edit,
            new ToolArguments(map("path", "main.txt", "old", "stale", "new", "after")));
    policy.onToolCompleted(
        run,
        failedEdit,
        ToolResult.error("file_tool_error", "search_match_count:0:expected=1:actual=0", false));
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision(policy, failedEdit).kind());
  }

  @Test
  public void managedPlanTracksEachCanonicalFileAndTreatsEditToolsAsAlternatives()
      throws Exception {
    Files.write(new File(root, "index.html").toPath(), "page".getBytes(StandardCharsets.UTF_8));
    Files.write(new File(root, "styles.css").toPath(), "style".getBytes(StandardCharsets.UTF_8));
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("syntax_check", "browser_test")
            .requireQualityReview("ui_product")
            .requireManagedPlan("ui_product")
            .build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("rewrite", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("browser_test", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(41L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "修改页面和样式",
            "quality_mode", "interface_product",
            "planned_files",
            Arrays.asList(
                map("path", "index.html", "action", "edit"),
                map("path", "styles.css", "action", "edit")),
            "steps",
            Arrays.asList(
                step("discover", "读取上下文", "discover", "read_file"),
                step("index-step", "修改页面", "implement", "search_replace", "rewrite"),
                step("style-step", "修改样式", "implement", "search_replace", "rewrite"),
                step("verify", "执行验证", "verify", "syntax_check", "browser_test"),
                step("quality", "质量审查", "quality", "quality_review"))));

    coordinator.recordAndDecorate(
        generation,
        "read_file",
        new ToolArguments(map("path", "index.html")),
        ToolResult.success(map("path", "index.html", "content", "source")));
    ToolResult indexEdited =
        coordinator.recordAndDecorate(
            generation,
            "search_replace",
            new ToolArguments(map("path", "index.html")),
            ToolResult.success(map("path", "index.html", "changed", true)));
    assertEquals("style-step", currentStepId(indexEdited.data().get("plan_state")));
    assertTrue(
        ((List<?>) ((Map<?, ?>) indexEdited.data().get("plan_state")).get("missing_evidence"))
            .contains("edit:styles.css"));

    ToolResult styleEdited =
        coordinator.recordAndDecorate(
            generation,
            "search_replace",
            new ToolArguments(map("path", "styles.css")),
            ToolResult.success(map("path", "styles.css", "changed", true)));
    assertEquals("style-step", currentStepId(styleEdited.data().get("plan_state")));
    assertFalse(
        ((List<?>) ((Map<?, ?>) styleEdited.data().get("plan_state")).get("missing_evidence"))
            .contains("rewrite"));
    assertEquals(
        Collections.singletonList("verify:syntax_check"),
        ((Map<?, ?>) styleEdited.data().get("plan_state")).get("missing_evidence"));

    coordinator.recordAndDecorate(
        generation,
        "syntax_check",
        ToolArguments.empty(),
        ToolResult.success(map("passed", true)));
    ToolResult browser =
        coordinator.recordAndDecorate(
            generation,
            "browser_test",
            new ToolArguments(map("scenarios", Collections.singletonList(staticScenario("smoke")))),
            passingBrowser("smoke"));
    assertEquals("quality", currentStepId(browser.data().get("plan_state")));
    coordinator.recordAndDecorate(
        generation,
        "quality_review",
        ToolArguments.empty(),
        ToolResult.success(
            map(
                "passed", true,
                "blocking_gaps", Collections.emptyList(),
                "claimed_but_unsupported", Collections.emptyList(),
                "minimal_version_risk", false)));
    assertTrue(coordinator.isComplete());

    ToolResult changedAgain =
        coordinator.recordAndDecorate(
            generation,
            "rewrite",
            new ToolArguments(map("path", "index.html")),
            ToolResult.success(map("path", "index.html", "changed", true)));
    assertFalse(coordinator.isComplete());
    assertEquals("style-step", currentStepId(changedAgain.data().get("plan_state")));
    assertTrue(String.valueOf(changedAgain.data().get("plan_state")).length() <= 800);
  }

  @Test
  public void plannedFileEvidenceRequiresTheRealResolvedPath() throws Exception {
    Files.write(new File(root, "other.txt").toPath(), "other".getBytes(StandardCharsets.UTF_8));
    CodeValidationContract contract =
        CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(42L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "修改两个文件",
            "planned_files",
            Arrays.asList(
                map("path", "main.txt", "action", "edit"),
                map("path", "other.txt", "action", "edit")),
            "steps",
            Collections.singletonList(
                step("implement", "修改全部文件", "implement", "search_replace", "rewrite"))));
    coordinator.recordAndDecorate(
        generation,
        "search_replace",
        new ToolArguments(map("path", "unplanned.txt")),
        ToolResult.success(map("resolved_path", "unplanned.txt", "changed", true)));
    ToolResult otherPath =
        coordinator.recordAndDecorate(
            generation,
            "search_replace",
            new ToolArguments(map("path", "other.txt")),
            ToolResult.success(map("resolved_path", "other.txt", "changed", true)));
    Map<?, ?> state = (Map<?, ?>) otherPath.data().get("plan_state");
    assertEquals("implement", currentStepId(state));
    List<?> missing = (List<?>) state.get("missing_evidence");
    assertTrue(missing.contains("edit:main.txt"));
    assertFalse(missing.contains("edit:other.txt"));
  }

  @Test
  public void pathSpecificMissingEvidenceRemainsCompact() {
    CodeValidationContract contract =
        CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(43L, "s", "workspace", "task"));
    List<Map<String, Object>> files = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      files.add(
          map(
              "path",
              "very/long/generated/component/path/number-"
                  + index
                  + "/responsive-interaction-styles.css",
              "action",
              "edit"));
    }
    Map<String, Object> result =
        coordinator.acceptPlan(
            map(
                "goal", "修改多个长路径文件",
                "planned_files", files,
                "steps",
                Collections.singletonList(
                    step("implement", "完成全部文件实现", "implement", "search_replace"))));
    Object state = result.get("plan_state");
    assertTrue(String.valueOf(state).length() <= 800);
    assertEquals(8, ((List<?>) ((Map<?, ?>) state).get("missing_evidence")).size());
  }

  @Test
  public void invalidDirectoryOutsideAndSymlinkPathsNeverBecomeManagedEvidence()
      throws Exception {
    File directory = new File(root, "folder");
    assertTrue(directory.mkdir());
    File outside = Files.createTempDirectory("aiw-outside").toFile();
    File outsideFile = new File(outside, "outside.txt");
    Files.write(outsideFile.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
    File escape = new File(root, "escape.txt");
    try {
      Files.createSymbolicLink(escape.toPath(), outsideFile.toPath());
      Map<String, CodeToolRole> roles = new LinkedHashMap<>();
      roles.put("read_file", CodeToolRole.READ);
      roles.put("search_replace", CodeToolRole.EDIT);
      roles.put("syntax_check", CodeToolRole.VERIFY);
      ManagedCodePlanCoordinator coordinator =
          new ManagedCodePlanCoordinator(
              CodePlanningMode.ADAPTIVE,
              CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build(),
              roles,
              workspace);
      coordinator.onRunStarted(new AgentRunContext(44L, "s", "workspace", "task"));
      long generation = coordinator.generation();
      Map<String, Object> plan =
          coordinator.acceptPlan(
              map(
                  "goal", "安全修改",
                  "steps",
                  Arrays.asList(
                      step("discover", "读取", "discover", "read_file"),
                      step("implement", "修改", "implement", "search_replace"),
                      step("verify", "验证", "verify", "syntax_check"))));
      assertEquals("discover", currentStepId(plan.get("plan_state")));

      for (String path : Arrays.asList("folder", "../outside.txt", "escape.txt")) {
        ToolResult invalidRead =
            coordinator.recordAndDecorate(
                generation,
                "read_file",
                new ToolArguments(map("path", path)),
                ToolResult.success(map("path", path, "content", "misreported")));
        assertFalse(invalidRead.data().containsKey("plan_state"));
      }
      ToolResult validRead =
          coordinator.recordAndDecorate(
              generation,
              "read_file",
              new ToolArguments(map("path", "main.txt")),
              ToolResult.success(map("path", "main.txt", "content", "before")));
      assertEquals("implement", currentStepId(validRead.data().get("plan_state")));

      for (String path : Arrays.asList("folder", "../outside.txt", "escape.txt")) {
        ToolResult invalidWrite =
            coordinator.recordAndDecorate(
                generation,
                "search_replace",
                new ToolArguments(map("path", path)),
                ToolResult.success(map("path", path, "changed", true)));
        assertFalse(invalidWrite.data().containsKey("plan_state"));
      }
      ToolResult validWrite =
          coordinator.recordAndDecorate(
              generation,
              "search_replace",
              new ToolArguments(map("path", "main.txt")),
              ToolResult.success(map("path", "main.txt", "changed", true)));
    assertEquals("implement", currentStepId(validWrite.data().get("plan_state")));
    assertEquals(
        Collections.singletonList("verify:syntax_check"),
        ((Map<?, ?>) validWrite.data().get("plan_state")).get("missing_evidence"));
    } finally {
      Files.deleteIfExists(escape.toPath());
      delete(outside);
    }
  }

  @Test
  public void createNewConflictRebindsOnlyTheRequestedPlannedFile() throws Exception {
    File requested = new File(root, "entry.html");
    Files.write(requested.toPath(), "precreated".getBytes(StandardCharsets.UTF_8));
    CodeValidationContract contract =
        CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("create_file", CodeToolRole.CREATE);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(45L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    Map<String, Object> planArguments =
        map(
            "goal", "创建入口和备用入口",
            "planned_files",
            Arrays.asList(
                map("path", "entry.html", "action", "create"),
                map("path", "entry-1.html", "action", "create")),
            "steps",
            Collections.singletonList(
                step("implement", "创建两个入口", "implement", "create_file")));
    coordinator.acceptPlan(planArguments);

    Files.write(
        new File(root, "entry-1.html").toPath(),
        "created by conflict resolution".getBytes(StandardCharsets.UTF_8));
    ToolArguments transformed =
        new ToolArguments(
            map(
                "path", "entry-1.html",
                "__requested_path", "entry.html",
                "__conflict_resolution", "create_new"));
    ToolResult created =
        coordinator.recordAndDecorate(
            generation,
            "create_file",
            transformed,
            ToolResult.success(
                map(
                    "requested_path", "entry.html",
                    "resolved_path", "entry-1.html",
                    "path", "entry-1.html",
                    "conflict_resolution", "create_new",
                    "created", true)));
    Map<?, ?> state = (Map<?, ?>) created.data().get("plan_state");
    assertEquals("implement", currentStepId(state));
    List<?> missing = (List<?>) state.get("missing_evidence");
    assertEquals(Collections.singletonList("create:entry-1.html"), missing);

    Map<String, Object> replanned = coordinator.acceptPlan(planArguments);
    Map<?, ?> replannedState = (Map<?, ?>) replanned.get("plan_state");
    assertEquals("implement", currentStepId(replannedState));
    assertEquals(
        Collections.singletonList("create:entry-1.html"),
        replannedState.get("missing_evidence"));
  }

  @Test
  public void mismatchedCreatePathsWithoutVerifiedCreateNewMetadataAreRejected()
      throws Exception {
    Files.write(
        new File(root, "requested.html").toPath(),
        "requested".getBytes(StandardCharsets.UTF_8));
    Files.write(
        new File(root, "different.html").toPath(),
        "different".getBytes(StandardCharsets.UTF_8));
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("create_file", CodeToolRole.CREATE);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE,
            CodeValidationContract.builder().build(),
            roles,
            workspace);
    coordinator.onRunStarted(new AgentRunContext(46L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "创建入口",
            "planned_files", Collections.singletonList(map("path", "requested.html", "action", "create")),
            "steps", Collections.singletonList(step("implement", "创建入口", "implement", "create_file"))));
    ToolResult rejected =
        coordinator.recordAndDecorate(
            generation,
            "create_file",
            new ToolArguments(map("path", "requested.html")),
            ToolResult.success(
                map(
                    "requested_path", "wrong.html",
                    "resolved_path", "different.html",
                    "conflict_resolution", "create_new",
                    "created", true)));
    assertFalse(rejected.data().containsKey("plan_state"));
    assertFalse(coordinator.isComplete());
  }

  @Test
  public void legacyPlannedFileWithoutActionCanSafelyRebindCreateNew() throws Exception {
    Files.write(
        new File(root, "legacy.html").toPath(),
        "precreated".getBytes(StandardCharsets.UTF_8));
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("create_file", CodeToolRole.CREATE);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(
            CodePlanningMode.ADAPTIVE,
            CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build(),
            roles,
            workspace);
    coordinator.onRunStarted(new AgentRunContext(47L, "s", "workspace", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "兼容旧计划创建入口",
            "planned_files", Collections.singletonList(map("path", "legacy.html")),
            "steps", Collections.singletonList(step("implement", "创建入口", "implement", "create_file"))));
    Files.write(
        new File(root, "legacy-1.html").toPath(),
        "created".getBytes(StandardCharsets.UTF_8));
    ToolResult created =
        coordinator.recordAndDecorate(
            generation,
            "create_file",
            new ToolArguments(
                map(
                    "path", "legacy-1.html",
                    "__requested_path", "legacy.html",
                    "__conflict_resolution", "create_new")),
            ToolResult.success(
                map(
                    "requested_path", "legacy.html",
                    "resolved_path", "legacy-1.html",
                    "conflict_resolution", "create_new",
                    "created", true)));
    assertTrue(created.data().containsKey("plan_state"));
    assertFalse("implement".equals(currentStepId(created.data().get("plan_state"))));
  }

  @Test
  public void partialSuccessAndErrorPreserveStatusAndInvalidateCurrentEvidence() {
    for (boolean error : Arrays.asList(false, true)) {
      ManagedCodePlanCoordinator coordinator = managedPlan("edit", 50L + (error ? 1 : 0), true);
      completeManagedPlan(coordinator, "search_replace", true);
      assertTrue(coordinator.isComplete());

      Map<String, Object> partialData =
          map(
              "path", "main.txt",
              "changed", true,
              "applied_count", 1);
      if (!error) {
        partialData.put("requested_count", 2);
        partialData.put("failed_count", 1);
      }
      ToolResult source = error
          ? ToolResult.error("partial_write", "one replacement failed", true, partialData)
          : ToolResult.success(partialData);
      ToolResult decorated =
          coordinator.recordAndDecorate(
              coordinator.generation(),
              "search_replace",
              new ToolArguments(map("path", "main.txt")),
              source);

      assertEquals(source.status(), decorated.status());
      assertEquals(source.errorCode(), decorated.errorCode());
      assertEquals(source.retryable(), decorated.retryable());
      assertTrue(decorated.data().containsKey("plan_state"));
      assertEquals("implement", currentStepId(decorated.data().get("plan_state")));
      assertTrue(
          ((List<?>) ((Map<?, ?>) decorated.data().get("plan_state")).get("missing_evidence"))
              .contains("edit:main.txt"));
      assertFalse(coordinator.isComplete());
      assertFalse(coordinator.hasCurrentEvidence("syntax_check"));
      assertFalse(coordinator.hasCurrentEvidence("quality_review"));
    }
  }

  @Test
  public void completeEditRepairsPartialEditWithoutRequiringOriginalCreateAgain() {
    ManagedCodePlanCoordinator coordinator = managedPlan("create", 52L, false);
    completeManagedPlan(coordinator, "create_file", false);
    assertTrue(coordinator.isComplete());

    ToolResult partial =
        coordinator.recordAndDecorate(
            coordinator.generation(),
            "search_replace",
            new ToolArguments(map("path", "main.txt")),
            ToolResult.success(
                map(
                    "path", "main.txt",
                    "changed", true,
                    "applied_count", 1,
                    "failed_indexes", Collections.singletonList(1))));
    assertEquals("implement", currentStepId(partial.data().get("plan_state")));
    assertFalse(coordinator.isComplete());

    ToolResult repaired =
        coordinator.recordAndDecorate(
            coordinator.generation(),
            "search_replace",
            new ToolArguments(map("path", "main.txt")),
            ToolResult.success(map("path", "main.txt", "changed", true, "applied_count", 1)));
    assertEquals("implement", currentStepId(repaired.data().get("plan_state")));
    assertEquals(
        Collections.singletonList("verify:syntax_check"),
        ((Map<?, ?>) repaired.data().get("plan_state")).get("missing_evidence"));
    assertFalse(
        ((List<?>) ((Map<?, ?>) repaired.data().get("plan_state")).get("missing_evidence"))
            .contains("create:main.txt"));
    coordinator.recordAndDecorate(
        coordinator.generation(),
        "syntax_check",
        ToolArguments.empty(),
        ToolResult.success(map("passed", true)));
    assertTrue(coordinator.isComplete());
  }

  @Test
  public void failedWriteWithoutMutationDoesNotRegressCompletedPlan() {
    ManagedCodePlanCoordinator coordinator = managedPlan("edit", 53L, true);
    completeManagedPlan(coordinator, "search_replace", true);
    assertTrue(coordinator.isComplete());

    ToolResult failed =
        coordinator.recordAndDecorate(
            coordinator.generation(),
            "search_replace",
            new ToolArguments(map("path", "main.txt")),
            ToolResult.error(
                "not_applied",
                "no replacement was applied",
                true,
                map(
                    "path", "main.txt",
                    "changed", false,
                    "requested_count", 1,
                    "applied_count", 0,
                    "failed_count", 1)));
    assertEquals(ToolResult.Status.ERROR, failed.status());
    assertTrue(failed.retryable());
    assertFalse(failed.data().containsKey("plan_state"));
    assertTrue(coordinator.isComplete());
    assertTrue(coordinator.hasCurrentEvidence("syntax_check"));
    assertTrue(coordinator.hasCurrentEvidence("quality_review"));
  }

  @Test
  public void latestVerifyAndQualityFailuresRevokeEvidenceAndCanRecover() {
    ManagedCodePlanCoordinator coordinator = managedPlan("edit", 54L, true);
    completeManagedPlan(coordinator, "search_replace", true);
    assertTrue(coordinator.isComplete());

    ToolResult failedVerify =
        coordinator.recordAndDecorate(
            coordinator.generation(),
            "syntax_check",
            ToolArguments.empty(),
            ToolResult.success(map("passed", false)));
    assertEquals("implement", currentStepId(failedVerify.data().get("plan_state")));
    assertFalse(coordinator.isComplete());
    coordinator.recordAndDecorate(
        coordinator.generation(),
        "syntax_check",
        ToolArguments.empty(),
        ToolResult.success(map("passed", true)));
    assertTrue(coordinator.isComplete());

    ToolResult erroredVerify =
        coordinator.recordAndDecorate(
            coordinator.generation(),
            "syntax_check",
            ToolArguments.empty(),
            ToolResult.error("syntax_failed", "syntax failed", true, map("passed", false)));
    assertEquals(ToolResult.Status.ERROR, erroredVerify.status());
    assertTrue(erroredVerify.retryable());
    assertEquals("implement", currentStepId(erroredVerify.data().get("plan_state")));
    assertFalse(coordinator.isComplete());
    coordinator.recordAndDecorate(
        coordinator.generation(),
        "syntax_check",
        ToolArguments.empty(),
        ToolResult.success(map("passed", true)));
    assertTrue(coordinator.isComplete());

    ToolResult erroredQuality =
        coordinator.recordAndDecorate(
            coordinator.generation(),
            "quality_review",
            ToolArguments.empty(),
            ToolResult.error("quality_failed", "quality failed", false, map("passed", false)));
    assertEquals(ToolResult.Status.ERROR, erroredQuality.status());
    assertEquals("quality", currentStepId(erroredQuality.data().get("plan_state")));
    assertFalse(coordinator.isComplete());
    coordinator.recordAndDecorate(
        coordinator.generation(),
        "quality_review",
        ToolArguments.empty(),
        passingQuality());
    assertTrue(coordinator.isComplete());
  }

  @Test
  public void everyPartialIndicatorAndRequestedCountMismatchLeavesWriteUnresolved() {
    List<Map<String, Object>> indicators =
        Arrays.asList(
            map("partial_apply", true),
            map("failed_count", 1),
            map("skipped_count", 1),
            map("failed_indexes", Collections.singletonList(0)),
            map("skipped_indexes", Collections.singletonList(0)),
            map("failed_replacements", Collections.singletonList("old")),
            map("skipped_replacements", Collections.singletonList("old")),
            map("failed_units", Collections.singletonList("unit")),
            map("skipped_units", Collections.singletonList("unit")),
            map("failures", Collections.singletonList("failure")),
            map("errors", Collections.singletonList("error")),
            map("results", Collections.singletonList(map("status", "failed"))),
            map("requested_count", 2, "applied_count", 1, "no_change_count", 0));
    long runId = 60L;
    for (Map<String, Object> indicator : indicators) {
      ManagedCodePlanCoordinator coordinator = managedPlan("edit", runId++, false);
      completeManagedPlan(coordinator, "search_replace", false);
      Map<String, Object> data = new LinkedHashMap<>(indicator);
      data.put("path", "main.txt");
      data.put("changed", true);
      if (!data.containsKey("applied_count")) data.put("applied_count", 1);
      ToolResult partial =
          coordinator.recordAndDecorate(
              coordinator.generation(),
              "search_replace",
              new ToolArguments(map("path", "main.txt")),
              ToolResult.success(data));
      assertEquals("indicator=" + indicator, "implement", currentStepId(partial.data().get("plan_state")));
      assertFalse("indicator=" + indicator, coordinator.isComplete());
      assertFalse("indicator=" + indicator, coordinator.hasCurrentEvidence("syntax_check"));
    }
  }

  @Test
  public void implementationFileMappingNeverExpandsPlanBeyondFiveSteps() {
    Map<String, Object> normalized =
        new CodePlanNormalizer(
                CodeValidationContract.builder()
                    .defaultRequiredEvidence("syntax_check", "browser_test")
                    .build())
            .normalize(
                map(
                    "goal", "完成混合文件任务",
                    "quality_mode", "interface_product",
                    "planned_files",
                    Arrays.asList(
                        map("path", "new.html", "action", "create"),
                        map("path", "main.txt", "action", "edit"),
                        map("path", "unknown.asset")),
                    "steps",
                    Arrays.asList(
                        step("discover", "读取上下文", "discover", "read_file"),
                        step("implement-a", "先处理一部分", "implement", "search_replace"),
                        step("implement-b", "再处理一部分", "implement", "create_file"),
                        step("verify", "执行验证", "verify", "syntax_check", "browser_test"),
                        step("quality", "质量审查", "quality", "quality_review"))));
    List<?> steps = (List<?>) normalized.get("steps");
    assertEquals(5, steps.size());
    Set<String> expected = new LinkedHashSet<>();
    for (Object raw : (List<?>) normalized.get("planned_files")) {
      expected.add(String.valueOf(((Map<?, ?>) raw).get("file_id")));
    }
    Set<String> actual = new LinkedHashSet<>();
    for (Object raw : steps) {
      Map<?, ?> step = (Map<?, ?>) raw;
      if ("implement".equals(step.get("phase"))) {
        for (Object ref : (List<?>) step.get("file_refs")) actual.add(String.valueOf(ref));
      }
    }
    assertEquals(expected, actual);
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
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(31L, "s", "w", "task"));
    long generation = coordinator.generation();
    coordinator.acceptPlan(
        map(
            "goal", "ui",
            "quality_mode", "interface_product",
            "verification_plan", Arrays.asList("syntax_check", "browser_test"),
            "steps", Collections.emptyList()));
    ToolResult read =
        ToolResult.success(map("operation", "read_file", "path", "main.txt", "content", "source"));
    ToolResult edit =
        ToolResult.success(map("operation", "search_replace", "path", "main.txt", "changed", true));
    ToolResult syntax = ToolResult.success(map("operation", "syntax_check", "passed", true));
    ToolResult browser = passingBrowser("smoke");
    ToolResult quality = ToolResult.success(
        map(
            "operation", "quality_review",
            "passed", true,
            "blocking_gaps", Collections.emptyList(),
            "claimed_but_unsupported", Collections.emptyList(),
            "minimal_version_risk", false));
    ToolArguments mainPath = new ToolArguments(map("path", "main.txt"));
    coordinator.recordAndDecorate(generation, "read_file", mainPath, read);
    coordinator.recordAndDecorate(generation, "search_replace", mainPath, edit);
    coordinator.recordAndDecorate(generation, "syntax_check", syntax);
    coordinator.recordAndDecorate(
        generation,
        "browser_test",
        new ToolArguments(map("scenarios", Collections.singletonList(staticScenario("smoke")))),
        browser);
    coordinator.recordAndDecorate(generation, "quality_review", quality);
    CodeCompletionValidator validator = new CodeCompletionValidator(contract, coordinator);
    List<ToolResult> evidence = new ArrayList<>(Arrays.asList(
        read, edit, syntax, browser, quality, finalizeEvidence("completed", "ui_product")));
    assertTrue(validate(validator, evidence).passed());

    coordinator.recordAndDecorate(generation, "search_replace", mainPath, edit);
    evidence.add(evidence.size() - 1, edit);
    ValidationResult stale = validate(validator, evidence);
    assertFalse(stale.passed());
    assertFalse(hasIssue(stale, "managed_plan_incomplete"));
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

  private ManagedCodePlanCoordinator managedPlan(
      String action, long runId, boolean qualityRequired) {
    CodeValidationContract contract =
        CodeValidationContract.builder().defaultRequiredEvidence("syntax_check").build();
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("list_dir", CodeToolRole.DISCOVER);
    roles.put("create_file", CodeToolRole.CREATE);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("rewrite", CodeToolRole.EDIT);
    roles.put("syntax_check", CodeToolRole.VERIFY);
    roles.put("quality_review", CodeToolRole.QUALITY);
    ManagedCodePlanCoordinator coordinator =
        new ManagedCodePlanCoordinator(CodePlanningMode.ADAPTIVE, contract, roles, workspace);
    coordinator.onRunStarted(new AgentRunContext(runId, "session", "workspace", "task"));
    List<Map<String, Object>> steps =
        new ArrayList<>(
            Arrays.asList(
                step("discover", "确认工作区", "discover", "list_dir"),
                step("implement", "完成文件实现", "implement", "create_file", "search_replace"),
                step("verify", "执行验证", "verify", "syntax_check")));
    if (qualityRequired) {
      steps.add(step("quality", "质量审查", "quality", "quality_review"));
    }
    coordinator.acceptPlan(
        map(
            "goal", "完成受管文件任务",
            "quality_mode", qualityRequired ? "interface_product" : "standard",
            "planned_files", Collections.singletonList(map("path", "main.txt", "action", action)),
            "steps", steps));
    coordinator.recordAndDecorate(
        coordinator.generation(),
        "list_dir",
        ToolArguments.empty(),
        ToolResult.success(map("path", ".", "items", Collections.singletonList("main.txt"))));
    return coordinator;
  }

  private static void completeManagedPlan(
      ManagedCodePlanCoordinator coordinator, String writeTool, boolean qualityRequired) {
    coordinator.recordAndDecorate(
        coordinator.generation(),
        writeTool,
        new ToolArguments(map("path", "main.txt")),
        ToolResult.success(map("path", "main.txt", "changed", true, "applied_count", 1)));
    coordinator.recordAndDecorate(
        coordinator.generation(),
        "syntax_check",
        ToolArguments.empty(),
        ToolResult.success(map("passed", true)));
    if (qualityRequired) {
      coordinator.recordAndDecorate(
          coordinator.generation(), "quality_review", ToolArguments.empty(), passingQuality());
    }
  }

  private static ToolResult passingQuality() {
    return ToolResult.success(
        map(
            "passed", true,
            "blocking_gaps", Collections.emptyList(),
            "claimed_but_unsupported", Collections.emptyList(),
            "minimal_version_risk", false));
  }

  private static Map<String, Object> dynamicScenario(String id) {
    return map(
        "id", id,
        "description", "执行 " + id,
        "actions", Collections.singletonList(
            map("type", "click", "selector", "#control-" + id)),
        "expectations", Collections.singletonList(
            map(
                "type", "js_boolean",
                "expression", "document.body.getAttribute('data-" + id + "') === 'true'",
                "transition", "false_to_true")));
  }

  private static Map<String, Object> staticScenario(String id) {
    return map(
        "id", id,
        "description", "检查 " + id,
        "actions", Collections.emptyList(),
        "expectations", Collections.singletonList(
            map(
                "type", "selector_exists",
                "selector", "body",
                "transition", "eventually_true")));
  }

  private static Map<String, Object> checkpointScenario(String id) {
    return map(
        "id", id,
        "actions", Arrays.asList(
            map("type", "click", "selector", "#start-" + id),
            map(
                "type", "wait_for",
                "expectation", map(
                    "type", "js_boolean",
                    "expression", "document.body.dataset.ready === 'true'",
                    "transition", "false_to_true")),
            map("type", "click", "selector", "#finish-" + id)),
        "expectations", Collections.singletonList(
            map(
                "type", "selector_exists",
                "selector", "#done-" + id,
                "transition", "eventually_true")));
  }

  private static ToolResult passingBrowser(String... ids) {
    return passingBrowserWithHash("test-plan-1", ids);
  }

  private static ToolResult passingBrowserWithHash(String planHash, String... ids) {
    List<String> scenarioIds = Arrays.asList(ids);
    List<Map<String, Object>> results = new ArrayList<>();
    for (String id : scenarioIds) results.add(map(
        "id", id,
        "passed", true,
        "action_trace", Collections.singletonList(
            map("index", 1, "action", "click_selector", "status", "success")),
        "actual_state", map("expectations", Collections.singletonList(
            map("expectation_index", 0, "status", "success")))));
    return ToolResult.success(
        map(
            "operation", "browser_test",
            "passed", true,
            "failure_kind", "none",
            "source_revision", "source-1",
            "test_plan_hash", planHash,
            "coverage_summary",
            map(
                "complete", true,
                "passed_scenario_ids", scenarioIds,
                "failed_scenario_ids", Collections.emptyList()),
            "scenario_results", results));
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

  private static Set<String> selectedNames(ToolSelection selection) {
    Set<String> names = new LinkedHashSet<>();
    for (ToolSpec spec : selection.tools()) names.add(spec.name());
    return names;
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
      ToolPolicy policy, ToolInvocation invocation) {
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

  private static String sha256(File file) throws Exception {
    byte[] bytes = Files.readAllBytes(file.toPath());
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder result = new StringBuilder();
    for (byte value : digest) result.append(String.format("%02x", value & 0xff));
    return result.toString();
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
