package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.AgentRunContext;
import com.cscjapp.aiworkbench.api.AgentRunLifecycle;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolInvocation;
import com.cscjapp.aiworkbench.api.ToolPolicy;
import com.cscjapp.aiworkbench.api.ToolPolicyCallback;
import com.cscjapp.aiworkbench.api.ToolPolicyDecision;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.WorkspaceAccess;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Requires current-run read evidence before an existing file is edited. */
public final class ReadBeforeEditPolicy implements ToolPolicy, AgentRunLifecycle {
  private final WorkspaceAccess workspace;
  private final Map<String, CodeToolRole> roles;
  private final Set<String> readPaths = new LinkedHashSet<>();
  private long activeRunId = -1L;

  public ReadBeforeEditPolicy(
      WorkspaceAccess workspace, Map<String, CodeToolRole> roles) {
    if (workspace == null) throw new IllegalArgumentException("workspace required");
    this.workspace = workspace;
    this.roles = roles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roles);
  }

  @Override
  public synchronized void onRunStarted(AgentRunContext context) {
    activeRunId = context.runId();
    readPaths.clear();
  }

  @Override
  public synchronized void onToolCompleted(
      AgentRunContext context, ToolInvocation invocation, ToolResult result) {
    if (context == null
        || context.runId() != activeRunId
        || result == null
        || !result.isSuccess()
        || role(invocation) != CodeToolRole.READ) {
      return;
    }
    collectPaths(invocation.arguments(), readPaths);
    collectResultPaths(result.data(), readPaths);
  }

  @Override
  public synchronized void onRunFinished(AgentRunContext context, String state) {
    if (context != null && context.runId() == activeRunId) {
      activeRunId = -1L;
      readPaths.clear();
    }
  }

  @Override
  public boolean supports(ToolInvocation invocation) {
    return role(invocation) == CodeToolRole.EDIT;
  }

  @Override
  public Cancellable evaluate(
      ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
    String source = invocation.arguments().getString("path", "");
    try {
      File target = workspace.resolveSafely(source);
      if (!target.exists() || target.isDirectory()) {
        callback.resolve(ToolPolicyDecision.proceed(invocation.arguments()));
        return Cancellable.NONE;
      }
      String canonical = target.getCanonicalPath();
      synchronized (this) {
        if (readPaths.contains(canonical)) {
          callback.resolve(ToolPolicyDecision.proceed(invocation.arguments()));
        } else {
          callback.resolve(
              ToolPolicyDecision.error(
                  "read_evidence_required",
                  "修改已有文件前必须在当前任务中先读取同一路径：" + canonical,
                  true));
        }
      }
    } catch (Exception error) {
      callback.resolve(
          ToolPolicyDecision.error(
              "invalid_edit_path",
              error.getMessage() == null ? "无法解析编辑路径" : error.getMessage(),
              false));
    }
    return Cancellable.NONE;
  }

  private CodeToolRole role(ToolInvocation invocation) {
    if (invocation == null || invocation.tool() == null || invocation.tool().spec() == null) {
      return CodeToolRole.OTHER;
    }
    CodeToolRole role = roles.get(invocation.tool().spec().name());
    return role == null ? CodeToolRole.OTHER : role;
  }

  private void collectPaths(ToolArguments arguments, Set<String> output) {
    add(arguments.getString("path", ""), output);
    Object targets = arguments.get("targets");
    if (!(targets instanceof List)) return;
    for (Object value : (List<?>) targets) {
      if (!(value instanceof Map)) continue;
      Map<?, ?> target = (Map<?, ?>) value;
      Object path = target.get("path");
      if (path != null && !String.valueOf(path).trim().isEmpty()) {
        add(String.valueOf(path), output);
      }
    }
  }

  private void collectResultPaths(Map<String, Object> data, Set<String> output) {
    if (data == null) return;
    for (String key : new String[] {"path", "resolved_path"}) {
      Object value = data.get(key);
      if (value != null) add(String.valueOf(value), output);
    }
    Object paths = data.get("paths");
    if (paths instanceof List) {
      for (Object value : (List<?>) paths) if (value != null) add(String.valueOf(value), output);
    }
  }

  private void add(String path, Set<String> output) {
    if (path == null || path.trim().isEmpty()) return;
    try {
      output.add(workspace.resolveSafely(path).getCanonicalPath());
    } catch (Exception ignored) {
      // Invalid paths remain real tool errors and never become read evidence.
    }
  }
}
