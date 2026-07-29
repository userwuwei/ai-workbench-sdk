package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.PromptContributor;
import com.cscjapp.aiworkbench.api.PromptPhase;
import com.cscjapp.aiworkbench.api.PromptSection;
import com.cscjapp.aiworkbench.api.TaskValidator;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolCallback;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolPolicy;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;
import com.cscjapp.aiworkbench.api.WorkspaceAccess;
import com.cscjapp.aiworkbench.tools.file.ExistingFileConflictPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in language-neutral Code Agent components composed by an app WorkbenchDefinition. */
public final class CodeAgentPreset {
  public static final String EXTRA_PLANNING_MODE = "code_agent_planning_mode";
  public static final String EXTRA_ATOMIC_EDIT_READ_GATE = "atomic_edit_read_gate";
  public static final String EXTRA_EDIT_VISIBLE_DURING_VERIFY = "edit_visible_during_verify";
  public static final String EXTRA_CONVERGENT_READ_PROMPT = "convergent_read_prompt";
  private final List<PromptContributor> promptContributors;
  private final List<AgentTool> tools;
  private final List<ToolPolicy> toolPolicies;
  private final List<TaskValidator> validators;

  private CodeAgentPreset(Builder builder) {
    if (builder.workspace == null) throw new IllegalStateException("workspace required");
    Map<String, CodeToolRole> roles = defaultRoles();
    roles.putAll(builder.roles);
    ManagedCodePlanCoordinator planCoordinator =
        new ManagedCodePlanCoordinator(
            builder.planningMode,
            builder.profile.verificationContract(),
            roles,
            builder.workspace,
            builder.editVisibleDuringVerify);

    boolean hasGoalDrivenReadPlan = hasTool(builder.languageTools, "read_plan");
    boolean hasBrowserTest = hasTool(builder.languageTools, "browser_test");
    List<PromptContributor> prompts = new ArrayList<>();
    prompts.add(
        commonPrompt(
            builder.planningMode,
            hasGoalDrivenReadPlan,
            hasBrowserTest,
            builder.convergentReadPrompt));
    if (!builder.profile.languageRules().isEmpty()) {
      prompts.add(
          context ->
              Collections.singletonList(
                  new PromptSection(
                      "code_language_" + builder.profile.id(),
                      PromptPhase.APP_RULES,
                      0,
                      18000,
                      builder.profile.languageRules())));
    }
    promptContributors = Collections.unmodifiableList(prompts);

    List<AgentTool> combinedTools = new ArrayList<>();
    for (ToolSpec spec :
        CodeMetaToolSchemas.create(builder.profile.metaToolExtensions())) {
      combinedTools.add(new CodeMetaTool(spec, planCoordinator));
    }
    combinedTools.addAll(builder.languageTools);
    Map<String, AgentTool> unique = new LinkedHashMap<>();
    for (AgentTool tool : combinedTools) {
      if (tool == null || tool.spec() == null) throw new IllegalArgumentException("invalid tool");
      String name = tool.spec().name();
      if (unique.put(name, new EvidenceTool(tool, planCoordinator)) != null) {
        throw new IllegalStateException("duplicate tool: " + name);
      }
    }
    tools = Collections.unmodifiableList(new ArrayList<>(unique.values()));

    List<ToolPolicy> policies = new ArrayList<>();
    policies.add(planCoordinator);
    policies.add(
        new ReadBeforeEditPolicy(builder.workspace, roles, builder.atomicEditReadGate));
    if (unique.containsKey("create_file")) {
      policies.add(new ExistingFileConflictPolicy(builder.workspace));
    }
    policies.addAll(builder.languagePolicies);
    toolPolicies = Collections.unmodifiableList(policies);

    List<TaskValidator> validation = new ArrayList<>();
    validation.add(
        new CodeCompletionValidator(builder.profile.verificationContract(), planCoordinator));
    validation.addAll(builder.languageValidators);
    validators = Collections.unmodifiableList(validation);
  }

  public static Builder builder(CodeLanguageProfile profile) {
    return new Builder(profile);
  }

  public List<PromptContributor> promptContributors() {
    return promptContributors;
  }

  public List<AgentTool> tools() {
    return tools;
  }

  public List<ToolPolicy> toolPolicies() {
    return toolPolicies;
  }

  public List<TaskValidator> validators() {
    return validators;
  }

  private static PromptContributor commonPrompt(
      CodePlanningMode planningMode,
      boolean hasGoalDrivenReadPlan,
      boolean hasBrowserTest,
      boolean convergentReadPrompt) {
    return context -> {
      boolean nativeTools = !Boolean.FALSE.equals(context.runtime().get("native_tools"));
      String protocol =
          nativeTools
              ? "需要行动时调用已注册的原生工具；禁止在普通文本中伪造工具调用或工作流数据。"
              : "需要行动时使用运行时支持的结构化工具协议；不要用 Markdown 围栏包裹工具数据。";
      String content =
          "你运行在通用 Code Agent 中。"
              + protocol
              + planningInstruction(planningMode)
              + "\n文件工具选择是强制协议：create_file 只用于当前项目尚不存在的新路径；已有文件的修复、优化、重构、重新布局、视觉升级和大范围调整都必须使用 search_replace。"
              + editEvidenceInstruction(convergentReadPrompt)
              + workflowInstruction()
              + readPlanningInstruction(hasGoalDrivenReadPlan, convergentReadPrompt)
              + browserVerificationInstruction(hasBrowserTest)
              + "\n已经生成完整文件内容不构成使用 create_file 的理由；planned_files 只表示任务涉及的文件，不表示允许重新创建；不得为了修改已有文件向 create_file 传入 overwrite。"
              + "\n只有运行时明确声明 precreated_entry_replace_allowed 的指定预创建入口，首次完整生成时才允许对已存在路径调用 create_file；该例外不授权其他文件。"
              + "\n工具失败、验证失败或质量 blocker 必须真实修复后重试，禁止虚构通过。"
              + "\n完成前执行适用的真实验证和 quality_review，最后调用 finalize_task。"
              + "\n无法继续或确实需要用户输入时，也必须通过 finalize_task 返回真实状态。";
      return Collections.singletonList(
          new PromptSection(
              "code_agent_protocol", PromptPhase.BASE, 0, 5000, content));
    };
  }

  private static String browserVerificationInstruction(boolean available) {
    if (!available) return "";
    return "\nbrowser_test 只使用以下合法形状：静态 actions=[] + expectations[{type:selector_exists,selector,transition:eventually_true}]；普通交互 actions[{type:click,selector}] + expectations[{type:js_boolean,expression,transition:false_to_true}]；多阶段在同一 actions 中使用 click → {type:wait_for,expectation:{type:js_boolean,expression,transition:false_to_true}} → click → {type:wait_for,expectation:{type:js_boolean,expression,transition:eventually_true}}。"
        + "\n断言 type 只能是 text_visible/selector_exists/url_contains/title_contains/js_boolean；eventually_true/false_to_true 只能写 transition。wait_for 只能位于 actions[] 且必须携带 expectation，不能当作 sleep；自动变化使用 actions=[]，禁止添加无因果点击凑交互。"
        + "\nselector、状态 token 和表达式必须逐字来自当前 revision，禁止凭记忆猜测。false_to_true 的条件必须在干净页面 baseline 为 false；动作后的关闭、消失或复位使用 eventually_true。精确文本仅用于用户明确要求的文案；数值状态优先使用独立数字节点、ARIA 或 data-*。"
        + "\n每个 interaction check 必须描述动作后可观察的终态；‘可见且可点击’只能作为静态检查，不能用动作前已经成立的条件硬凑 false_to_true。"
        + "\nsyntax 推荐 browser_test 时不得为方便测试提前改变产品行为；只有不存在独立 reading_brief 产品根因时，test_plan_invalid/test_expectation_mismatch 才只修正测试并重提 browser_test。"
        + "\nenvironment_failure 不能通过读取源码、修改产品、replan 或重复 finalize_task 解决；只修正验证调用并执行 recommended_next_action。"
        + "\n同时存在 reading_brief 与 test_retry_brief 时，只按 reading_brief 修复未被阻断的产品根因，禁止修改产品迎合测试；产品修复并通过 syntax_check 后，在下一次 browser_test 中修正 test_retry_brief 指出的测试问题。只有纯产品失败要求保持 actions、wait_for 和 expectations 语义及 Hash 不变回归。quality_review/finalize 的已验证行为只能来自 verified_behavior_evidence 和 action/checkpoint trace。";
  }

  private static String workflowInstruction() {
    return "\nrecommended_next_action 是当前首选动作，不是其他安全工具的执行禁令；已知仍有计划内修改时，先完成当前编辑批次，再调用 syntax_check。"
        + "\nrecommended_next_action=quality_review 时，直接依据当前 syntax/browser 证据提交 passed、blocking_gaps、minimal_version_risk；不要读取源码、replan 或传入 path。"
        + "\n同一文件中已经确定的修改优先合并到一次 search_replace.replacements[]；受输出大小或独立锚点限制时，允许在 syntax_check 前连续执行多次 search_replace。"
        + "\n只有测试根因时优先修正测试，禁止修改产品迎合错误断言。任何新写入都会使旧 syntax_check、browser_test 和 quality_review 证据失效，必须基于最新 revision 重新验证。";
  }

  private static String editEvidenceInstruction(boolean convergent) {
    String searchReplaceContract = " search_replace.old 推荐使用当前 revision 中 2～6 行的精确短窗口，不得超过 40 行或 3000 字符；单行 substring 仅在 expected_matches=1 且唯一命中时可用，重复文本必须分别增加上下文。预检已返回 candidate_windows/preferred_retry_old 时，直接修正整批 replacements，不为同一缺口重新读取。";
    if (!convergent) {
      return "\n修改已有文件前先读取真实内容；同一文件多个已确定修改点优先合并到一次 search_replace.replacements[]，old 必须逐字来自最新读取证据。" + searchReplaceContract;
    }
    return "\n同一文件多个已确定修改点优先合并到一次 search_replace.replacements[]；old 必须是当前 revision 中逐字准确的真实源码锚点。" + searchReplaceContract;
  }

  private static String readPlanningInstruction(boolean available, boolean convergent) {
    if (!convergent) {
      if (!available) return "";
      return "\n首次接触当前 revision 的普通源码文件时，优先只传 path 使用 read_file 完整读取；已知具体跨区域问题或验证失败证据时，可使用 read_plan.evidence_requirements 一次定位 DOM/CSS/函数/调用链。读取工具是非破坏性的，由你根据上下文自行选择。"
          + "\nread_plan.coverage_summary.ready_for_edit=true 时可使用 edit_anchor_pack 的真实源码进入合并 search_replace；为 false 时根据 missing_evidence_types/missing_signals 决定继续读取或调整判断，禁止猜测 old。";
    }
    String multiRegion = available
        ? "\n3. 涉及两个以上不连续区域、调用链、状态流或 DOM/CSS/JS 联动且缺少锚点时，只调用一次 read_plan.evidence_requirements 收集全部证据。"
        : "\n3. 涉及多个区域且 read_plan 不可用时，只做最少的定向读取收集精确锚点，禁止重复全文读取。";
    return "\n读取遵循最小充分证据原则："
        + "\n1. 当前 revision 已有逐字准确的 old 锚点时，直接编辑或验证，不为同步上下文重复读取。"
        + "\n2. 只缺一个连续符号或函数时，仅调用一次有界 read_file；范围必须来自当前 revision 的工具结果或错误窗口，禁止猜测行号。"
        + multiRegion
        + "\n4. 成功写入已经建立当前 revision；写入后有 old 就直接 search_replace，没有 old 才补一次有界读取或一次 read_plan。"
        + "\n5. read_plan.coverage_summary.ready_for_edit=true 后默认编辑或验证；为 false 时只补充明确列出的 missing evidence，禁止猜测 old。"
        + "\n6. 仅在首次接触小文件且确实需要全局理解时，才使用 path-only 完整 read_file。";
  }

  private static boolean hasTool(List<? extends AgentTool> tools, String name) {
    if (tools == null || name == null) return false;
    for (AgentTool tool : tools) {
      if (tool != null && tool.spec() != null && name.equals(tool.spec().name())) return true;
    }
    return false;
  }

  private static String planningInstruction(CodePlanningMode mode) {
    if (mode == CodePlanningMode.SKIP) {
      return "\n当前任务按简单流程执行，不要求 plan_task；直接使用必要的读取、修改和验证工具。";
    }
    String complex =
        "完整代码生成、多文件修改、功能集成、重构、界面、游戏、动画、可视化或复杂交互属于复杂任务；应基于已有证据调用 plan_task 建立短计划后再写入。真实证据变化时可再次 plan_task 调整剩余步骤；解释、注释、确定性替换和单点修复可直接执行。";
    return mode == CodePlanningMode.FORCE
        ? "\n当前任务要求首次写入前调用 plan_task。" + complex
        : "\n采用自适应规划：复杂任务优先建立短计划，但计划是执行引导而不是终态硬证据。" + complex;
  }

  private static Map<String, CodeToolRole> defaultRoles() {
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
    return roles;
  }

  public static final class Builder {
    private final CodeLanguageProfile profile;
    private WorkspaceAccess workspace;
    private final List<AgentTool> languageTools = new ArrayList<>();
    private final List<ToolPolicy> languagePolicies = new ArrayList<>();
    private final List<TaskValidator> languageValidators = new ArrayList<>();
    private final Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    private CodePlanningMode planningMode = CodePlanningMode.ADAPTIVE;
    private boolean editVisibleDuringVerify = true;
    private boolean convergentReadPrompt = true;
    private String atomicEditReadGate = "rewrite_only";

    private Builder(CodeLanguageProfile profile) {
      if (profile == null) throw new IllegalArgumentException("profile required");
      this.profile = profile;
    }

    public Builder workspace(WorkspaceAccess value) {
      workspace = value;
      return this;
    }

    public Builder languageTools(List<? extends AgentTool> values) {
      languageTools.clear();
      if (values != null) languageTools.addAll(values);
      return this;
    }

    public Builder languagePolicies(List<? extends ToolPolicy> values) {
      languagePolicies.clear();
      if (values != null) languagePolicies.addAll(values);
      return this;
    }

    public Builder languageValidators(List<? extends TaskValidator> values) {
      languageValidators.clear();
      if (values != null) languageValidators.addAll(values);
      return this;
    }

    public Builder toolRole(String toolName, CodeToolRole role) {
      if (toolName == null || toolName.trim().isEmpty() || role == null) {
        throw new IllegalArgumentException("toolName/role required");
      }
      roles.put(toolName.trim(), role);
      return this;
    }

    public Builder planningMode(CodePlanningMode value) {
      planningMode = value == null ? CodePlanningMode.ADAPTIVE : value;
      return this;
    }

    public Builder planningMode(String value) {
      return planningMode(CodePlanningMode.from(value));
    }

    /**
     * Compatibility setting retained for existing hosts. Managed plans now always keep their
     * exact-anchor search_replace path visible through verification and quality.
     */
    public Builder editVisibleDuringVerify(boolean value) {
      editVisibleDuringVerify = value;
      return this;
    }

    /** Uses the minimum-sufficient-evidence read decision contract in the common prompt. */
    public Builder convergentReadPrompt(boolean value) {
      convergentReadPrompt = value;
      return this;
    }

    /** Selects legacy all-edit gating or the production rewrite-only read gate. */
    public Builder atomicEditReadGate(String value) {
      atomicEditReadGate = "legacy".equalsIgnoreCase(value == null ? "" : value.trim())
          ? "legacy"
          : "rewrite_only";
      return this;
    }

    public CodeAgentPreset build() {
      return new CodeAgentPreset(this);
    }
  }

  private static final class EvidenceTool implements AgentTool {
    private final AgentTool delegate;
    private final ManagedCodePlanCoordinator planCoordinator;

    EvidenceTool(AgentTool delegate, ManagedCodePlanCoordinator planCoordinator) {
      this.delegate = delegate;
      this.planCoordinator = planCoordinator;
    }

    @Override
    public ToolSpec spec() {
      return delegate.spec();
    }

    @Override
    public boolean requestsFinalize() {
      return delegate.requestsFinalize();
    }

    @Override
    public Cancellable execute(
        ToolContext context, ToolArguments arguments, ToolCallback callback) {
      long runGeneration = planCoordinator.generation();
      ToolResult preflight = planCoordinator.preflightResult(spec().name(), arguments);
      if (preflight != null) {
        callback.onComplete(planCoordinator.recordAndDecorate(
            runGeneration, spec().name(), arguments, preflight));
        return Cancellable.NONE;
      }
      return delegate.execute(
          context,
          arguments,
          new ToolCallback() {
            @Override
            public void onProgress(
                String stage, long current, long total, String message) {
              callback.onProgress(stage, current, total, message);
            }

            @Override
            public void onComplete(ToolResult result) {
              ToolResult normalized = result;
              if (result != null) {
                Map<String, Object> data = new LinkedHashMap<>(result.data());
                if (!data.containsKey("operation")) data.put("operation", spec().name());
                if (result.isSuccess()) {
                  normalized = ToolResult.success(data);
                } else if (result.status() == ToolResult.Status.ERROR) {
                  data.put("passed", false);
                  data.put("tool_status", "error");
                  normalized = ToolResult.error(
                      result.errorCode(), result.message(), result.retryable(), data);
                }
              }
              callback.onComplete(planCoordinator.recordAndDecorate(
                  runGeneration, spec().name(), arguments, normalized));
            }
          });
    }
  }
}
