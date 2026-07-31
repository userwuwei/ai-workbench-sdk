package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolCallback;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;
import java.util.Collection;
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
    ToolArguments safeArguments = arguments == null ? ToolArguments.empty() : arguments;
    if (CodeAgentToolNames.QUALITY_REVIEW.equals(spec.name())) {
      String issue = qualityArgumentsIssue(safeArguments);
      if (!issue.isEmpty()) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", spec.name());
        data.put("recommended_next_action", CodeAgentToolNames.QUALITY_REVIEW);
        callback.onComplete(
            ToolResult.error(
                "invalid_quality_arguments",
                issue + "；请直接提交 {\"passed\":true,\"blocking_gaps\":[],"
                    + "\"minimal_version_risk\":false}，不要传 path。",
                true,
                data));
        return Cancellable.NONE;
      }
    }
    Map<String, Object> data = CodeAgentToolNames.PLAN_TASK.equals(spec.name())
        ? planCoordinator.acceptPlan(safeArguments.asMap())
        : new LinkedHashMap<>(safeArguments.asMap());
    if (CodeAgentToolNames.QUALITY_REVIEW.equals(spec.name())
        && !data.containsKey("quality_mode")
        && !planCoordinator.qualityMode().isEmpty()) {
      data.put("quality_mode", planCoordinator.qualityMode());
    }
    data.put("operation", spec.name());
    callback.onComplete(ToolResult.success(data));
    return Cancellable.NONE;
  }

  private static String qualityArgumentsIssue(ToolArguments arguments) {
    Map<String, Object> values = arguments.asMap();
    if (values.containsKey("path")) return "quality_review 不读取文件且不接收 path";
    Object passed = values.get("passed");
    Object gaps = values.get("blocking_gaps");
    Object risk = values.get("minimal_version_risk");
    if (!(passed instanceof Boolean)
        || !(gaps instanceof Collection)
        || !(risk instanceof Boolean)) {
      return "quality_review 必须提交 passed、blocking_gaps 和 minimal_version_risk";
    }
    for (Object gap : (Collection<?>) gaps) {
      if (!(gap instanceof String)) return "quality_review.blocking_gaps 必须是字符串数组";
    }
    boolean hasGaps = !((Collection<?>) gaps).isEmpty();
    if (Boolean.TRUE.equals(passed)
        && (hasGaps || Boolean.TRUE.equals(risk))) {
      return "quality_review 的通过结论与 blocking_gaps/minimal_version_risk 矛盾";
    }
    if (Boolean.FALSE.equals(passed)
        && !hasGaps
        && !Boolean.TRUE.equals(risk)) {
      return "quality_review 未通过时必须给出 blocking_gaps 或 minimal_version_risk";
    }
    return "";
  }
}
