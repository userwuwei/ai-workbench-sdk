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

  CodeMetaTool(ToolSpec spec) {
    this.spec = spec;
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
    Map<String, Object> data = new LinkedHashMap<>(arguments.asMap());
    data.put("operation", spec.name());
    callback.onComplete(ToolResult.success(data));
    return Cancellable.NONE;
  }
}
