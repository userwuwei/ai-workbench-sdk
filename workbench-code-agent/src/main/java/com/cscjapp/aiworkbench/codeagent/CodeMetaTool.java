package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolCallback;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;
import java.util.LinkedHashMap;
import java.util.Map;

final class CodeMetaTool implements AgentTool {
  private final ToolSpec spec;
  private final ManagedCodePlanCoordinator planCoordinator;

  CodeMetaTool(ToolSpec spec, ManagedCodePlanCoordinator planCoordinator) {
    this.spec = spec;
    this.planCoordinator = planCoordinator;
  }

  @Override
  public ToolSpec spec() {
    return spec;
  }

  @Override
  public boolean requestsFinalize() {
    return CodeAgentToolNames.FINALIZE_TASK.equals(spec.name());
  }

  @Override
  public Cancellable execute(
      ToolContext context, ToolArguments arguments, ToolCallback callback) {
    if (CodeAgentToolNames.PLAN_TASK.equals(spec.name())
        && arguments != null
        && arguments.has("__raw_arguments")) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("operation", spec.name());
      callback.onComplete(
          ToolResult.error(
              "invalid_tool_arguments",
              "plan_task 参数不是有效的 JSON 对象，请使用更短的合法参数重试。",
              true,
              data));
      return Cancellable.NONE;
    }
    Map<String, Object> data = CodeAgentToolNames.PLAN_TASK.equals(spec.name())
        ? planCoordinator.acceptPlan(arguments.asMap())
        : new LinkedHashMap<>(arguments.asMap());
    data.put("operation", spec.name());
    callback.onComplete(ToolResult.success(data));
    return Cancellable.NONE;
  }
}
