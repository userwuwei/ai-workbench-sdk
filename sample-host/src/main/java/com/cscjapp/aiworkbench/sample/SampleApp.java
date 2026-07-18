package com.cscjapp.aiworkbench.sample;

import android.app.Application;
import com.cscjapp.aiworkbench.android.AIWorkbench;
import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class SampleApp extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    AIWorkbench.install(
        this,
        WorkbenchSdkConfig.builder()
            .registerFactory("sample", r -> new Definition())
            .modelConfigProvider(
                r -> new ModelEndpoint("http://127.0.0.1:3000/v1", "", "test", 0.2, true, false))
            .build());
  }

  private static final class Definition implements WorkbenchDefinition {
    public String id() {
      return "sample";
    }

    public String displayName() {
      return "通用工作台示例";
    }

    public List<PromptContributor> promptContributors() {
      return Collections.singletonList(
          c ->
              Collections.singletonList(
                  new PromptSection("sample", PromptPhase.APP_RULES, 0, 1000, "使用 echo 工具处理请求。")));
    }

    public List<ContextProvider> contextProviders() {
      return Collections.emptyList();
    }

    public List<AgentTool> tools() {
      return Collections.singletonList(new EchoTool());
    }

    public List<ToolPolicy> toolPolicies() {
      return Collections.emptyList();
    }

    public List<TaskValidator> validators() {
      return Collections.emptyList();
    }

    public WorkbenchHost host() {
      return new WorkbenchHost() {
        public void openArtifact(String a) {}

        public void refreshArtifacts() {}

        public void handleAction(String a, ToolArguments v) {}

        public void onEvent(WorkbenchEvent e) {}
      };
    }
  }

  private static final class EchoTool implements AgentTool {
    public ToolSpec spec() {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("type", "object");
      return new ToolSpec("echo", "回显输入", s);
    }

    public Cancellable execute(ToolContext c, ToolArguments a, ToolCallback cb) {
      cb.onComplete(ToolResult.success(a.asMap()));
      return Cancellable.NONE;
    }
  }
}
