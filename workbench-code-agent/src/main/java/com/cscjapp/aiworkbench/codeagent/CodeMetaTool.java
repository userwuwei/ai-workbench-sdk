package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolCallback;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;

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
    callback.onComplete(planCoordinator.executeMeta(spec.name(), arguments));
    return Cancellable.NONE;
  }
}
