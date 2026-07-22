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
  private final List<PromptContributor> promptContributors;
  private final List<AgentTool> tools;
  private final List<ToolPolicy> toolPolicies;
  private final List<TaskValidator> validators;

  private CodeAgentPreset(Builder builder) {
    if (builder.workspace == null) throw new IllegalStateException("workspace required");
    Map<String, CodeToolRole> roles = defaultRoles();
    roles.putAll(builder.roles);
    ManagedCodePlanCoordinator planCoordinator =
        new ManagedCodePlanCoordinator(builder.planningMode, builder.profile.verificationContract(), roles);

    List<PromptContributor> prompts = new ArrayList<>();
    prompts.add(commonPrompt(builder.planningMode));
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
    policies.add(new ReadBeforeEditPolicy(builder.workspace, roles));
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

  private static PromptContributor commonPrompt(CodePlanningMode planningMode) {
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
              + "\n修改已有文件前先读取真实内容；同一文件多个修改点合并到一次 search_replace.replacements[]，old 必须逐字来自最新读取证据。"
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

  private static String planningInstruction(CodePlanningMode mode) {
    if (mode == CodePlanningMode.SKIP) {
      return "\n当前任务按简单流程执行，不要求 plan_task；直接使用必要的读取、修改和验证工具。";
    }
    String complex =
        "完整代码生成、多文件修改、功能集成、重构、界面、游戏、动画、可视化或复杂交互属于复杂任务，必须先调用 plan_task；解释、注释、确定性替换和单点修复可直接执行。";
    return mode == CodePlanningMode.FORCE
        ? "\n当前任务要求首次写入前调用 plan_task。" + complex
        : "\n采用自适应规划：" + complex;
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
                  runGeneration, spec().name(), normalized));
            }
          });
    }
  }
}
