package com.cscjapp.aiworkbench.sample;

import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ContextProvider;
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
import com.cscjapp.aiworkbench.api.WorkbenchAction;
import com.cscjapp.aiworkbench.api.WorkbenchContextItem;
import com.cscjapp.aiworkbench.api.WorkbenchDefinition;
import com.cscjapp.aiworkbench.api.WorkbenchHost;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.api.WorkbenchUiState;
import com.cscjapp.aiworkbench.api.WorkbenchUiStateProvider;
import com.cscjapp.aiworkbench.codeagent.CodeAgentPreset;
import com.cscjapp.aiworkbench.codeagent.CodeLanguageProfile;
import com.cscjapp.aiworkbench.codeagent.CodeToolRole;
import com.cscjapp.aiworkbench.codeagent.CodeValidationContract;
import com.cscjapp.aiworkbench.tools.file.ExistingFileConflictPolicy;
import com.cscjapp.aiworkbench.tools.file.FileToolSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlaygroundDefinition implements WorkbenchDefinition {
  private final PlaygroundRuntime runtime;
  private final WorkbenchLaunchRequest request;
  private final PlaygroundHost host;
  private final boolean codeAgentMode;
  private final List<AgentTool> tools;
  private final List<PromptContributor> prompts;
  private final List<ToolPolicy> policies;
  private final List<TaskValidator> validators;

  PlaygroundDefinition(
      PlaygroundRuntime runtime, WorkbenchLaunchRequest request, boolean codeAgentMode) {
    this.runtime = runtime;
    this.request = request;
    this.codeAgentMode = codeAgentMode;
    host = new PlaygroundHost(runtime);
    List<AgentTool> values = new ArrayList<>();
    values.add(new EchoTool());
    values.add(new VerifyWorkspaceTool());
    values.addAll(FileToolSet.standard(runtime.workspace()));
    List<AgentTool> wrapped = new ArrayList<>();
    for (AgentTool tool : values) wrapped.add(new LoggingTool(tool));
    if (codeAgentMode) {
      CodeLanguageProfile profile =
          CodeLanguageProfile.builder("playground")
              .languageRules(
                  "这是 Playground 的文本代码工作区。仅修改工作区内的文本文件，"
                      + "并使用 verify_workspace 验证 code-agent-demo.txt 的最终内容。")
              .verificationContract(
                  CodeValidationContract.builder()
                      .defaultRequiredEvidence("verify_workspace")
                      .exemptCompletionTypes("explain")
                      .requireQualityReview("feature_integration")
                      .build())
              .build();
      CodeAgentPreset preset =
          CodeAgentPreset.builder(profile)
              .workspace(runtime.workspace())
              .languageTools(wrapped)
              .toolRole("verify_workspace", CodeToolRole.VERIFY)
              .build();
      tools = preset.tools();
      prompts = preset.promptContributors();
      policies = preset.toolPolicies();
      validators = preset.validators();
    } else {
      tools = Collections.unmodifiableList(wrapped);
      prompts =
          Collections.singletonList(
              context ->
                  Collections.singletonList(
                      new PromptSection(
                          "playground-rules",
                          PromptPhase.APP_RULES,
                          100,
                          2400,
                          "你正在 AI Workbench SDK Playground 中运行。"
                              + "允许使用已注册工具操作沙箱工作区，并在完成后简洁说明真实结果。"
                              + "所有路径必须位于 Playground 工作区内。")));
      policies =
          Collections.singletonList(new ExistingFileConflictPolicy(runtime.workspace()));
      validators = Collections.emptyList();
    }
  }

  @Override
  public String id() {
    return codeAgentMode
        ? PlaygroundRuntime.CODE_DEFINITION_ID
        : PlaygroundRuntime.DEFINITION_ID;
  }

  @Override
  public String displayName() {
    if (codeAgentMode) return "Playground · 通用 Code Agent";
    return PlaygroundRuntime.MODE_REAL.equals(runtime.mode(request))
        ? "Playground · 真实模型"
        : "Playground · 离线模式";
  }

  @Override
  public List<PromptContributor> promptContributors() {
    return prompts;
  }

  @Override
  public List<ContextProvider> contextProviders() {
    return Collections.singletonList(
        context -> {
          List<PromptSection> sections = new ArrayList<>();
          for (String path : runtime.selectedContexts()) {
            try {
              String content = runtime.workspace().read(path);
              if (content.length() > 8000) content = content.substring(0, 8000);
              sections.add(
                  new PromptSection(
                      "context-" + path,
                      PromptPhase.CONTEXT,
                      0,
                      9000,
                      "文件：" + path + "\n" + content));
            } catch (Exception error) {
              runtime.log("context", "读取失败 " + path + "：" + error.getMessage());
            }
          }
          return sections;
        });
  }

  @Override
  public List<AgentTool> tools() {
    return tools;
  }

  @Override
  public List<ToolPolicy> toolPolicies() {
    return policies;
  }

  @Override
  public List<TaskValidator> validators() {
    return validators;
  }

  @Override
  public WorkbenchHost host() {
    return host;
  }

  @Override
  public WorkbenchUiStateProvider uiStateProvider() {
    return new WorkbenchUiStateProvider() {
      @Override
      public WorkbenchUiState current(
          WorkbenchLaunchRequest launchRequest,
          com.cscjapp.aiworkbench.api.ModelEndpoint endpoint) {
        List<WorkbenchContextItem> contextItems = new ArrayList<>();
        for (String path : runtime.selectedContexts()) {
          contextItems.add(new WorkbenchContextItem(path, path));
        }
        return WorkbenchUiState.builder()
            .projectName(displayName())
            .userName("Playground")
            .modelLabel(endpoint == null ? "未配置" : endpoint.modelId())
            .deepThinkingSupported(endpoint != null && endpoint.deepThinking())
            .contextSelectionVisible(true)
            .reportVisible(true)
            .contextItems(contextItems)
            .build();
      }

      @Override
      public Cancellable observe(WorkbenchLaunchRequest launchRequest, Runnable onChanged) {
        return runtime.observeState(onChanged);
      }
    };
  }

  @Override
  public List<WorkbenchAction> actions() {
    return Arrays.asList(
        WorkbenchAction.host("select_model", "模型配置", WorkbenchAction.Placement.TOOLBAR),
        WorkbenchAction.host("report", "模拟举报", WorkbenchAction.Placement.TOOLBAR),
        WorkbenchAction.prompt(
            "read_demo", "读取文件", "请读取 README.md，并说明 Playground 工作区内容。"),
        WorkbenchAction.prompt(
            "create_demo", "创建文件", "请创建一个 Playground 示例文件并写入测试内容。"),
        WorkbenchAction.prompt(
            "replace_demo", "替换文本", "请使用 search_replace 修改 demo.txt 中的初始文本。"));
  }

  private final class LoggingTool implements AgentTool {
    private final AgentTool delegate;

    LoggingTool(AgentTool delegate) {
      this.delegate = delegate;
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
      String name = spec().name();
      runtime.log("tool", name + " 开始，参数字段=" + arguments.asMap().keySet());
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
              runtime.log("tool", name + " 完成：" + result.status());
              if (result.isSuccess()
                  && Arrays.asList("create_file", "rewrite", "search_replace").contains(name)) {
                Object path = result.data().get("resolved_path");
                if (path == null) path = result.data().get("path");
                if (path != null) runtime.setLatestArtifact(String.valueOf(path));
                host.refreshArtifacts();
              }
              callback.onComplete(result);
            }
          });
    }
  }

  private static final class EchoTool implements AgentTool {
    @Override
    public ToolSpec spec() {
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("type", "object");
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("text", Collections.singletonMap("type", "string"));
      schema.put("properties", properties);
      schema.put("required", Collections.singletonList("text"));
      return new ToolSpec("echo", "回显参数，用于验证工具参数流和执行状态", schema);
    }

    @Override
    public Cancellable execute(
        ToolContext context, ToolArguments arguments, ToolCallback callback) {
      String text = arguments.getString("text", "");
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("text", text);
      result.put("length", text.length());
      callback.onComplete(ToolResult.success(result));
      return Cancellable.NONE;
    }
  }

  private final class VerifyWorkspaceTool implements AgentTool {
    @Override
    public ToolSpec spec() {
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("type", "object");
      schema.put(
          "properties",
          Collections.singletonMap("path", Collections.singletonMap("type", "string")));
      schema.put("required", Collections.singletonList("path"));
      return new ToolSpec(
          "verify_workspace", "读取文件并验证 Code Agent Playground 的修改结果", schema);
    }

    @Override
    public Cancellable execute(
        ToolContext context, ToolArguments arguments, ToolCallback callback) {
      String path = arguments.getString("path", "");
      try {
        String content = runtime.workspace().read(path);
        boolean passed = content.contains("Code Agent 闭环修改完成");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("passed", passed);
        result.put(
            "summary", passed ? "真实文件内容验证通过" : "目标文件不包含预期修改");
        callback.onComplete(
            passed
                ? ToolResult.success(result)
                : ToolResult.error("verification_failed", String.valueOf(result), true));
      } catch (Exception error) {
        callback.onComplete(
            ToolResult.error(
                "verification_failed",
                error.getMessage() == null ? "无法读取验证文件" : error.getMessage(),
                true));
      }
      return Cancellable.NONE;
    }
  }
}
