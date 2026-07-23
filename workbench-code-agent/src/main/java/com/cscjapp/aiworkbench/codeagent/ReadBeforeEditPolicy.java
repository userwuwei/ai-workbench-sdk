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
import java.util.logging.Logger;

/** Requires current-run read evidence before an existing file is edited. */
public final class ReadBeforeEditPolicy implements ToolPolicy, AgentRunLifecycle {
  private static final Logger LOGGER = Logger.getLogger(ReadBeforeEditPolicy.class.getName());
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
        || result == null) {
      return;
    }
    CodeToolRole completedRole = role(invocation);
    if (completedRole == CodeToolRole.READ && result.isSuccess()) {
      collectResultPaths(result.data(), readPaths);
    } else if ((completedRole == CodeToolRole.CREATE || completedRole == CodeToolRole.EDIT)
        && writeMayHaveChanged(result)) {
      Set<String> changedPaths = new LinkedHashSet<>();
      collectWritePaths(invocation, result.data(), changedPaths);
      readPaths.removeAll(changedPaths);
    }
  }

  private static boolean writeMayHaveChanged(ToolResult result) {
    Map<String, Object> data = result.data();
    if (Boolean.TRUE.equals(data.get("changed"))
        || Boolean.TRUE.equals(data.get("current_file_changed"))
        || Boolean.TRUE.equals(data.get("created"))
        || Boolean.TRUE.equals(data.get("overwritten"))
        || Boolean.TRUE.equals(data.get("partial_apply"))
        || positive(data.get("applied_count"))) return true;
    return result.isSuccess() && !Boolean.FALSE.equals(data.get("changed"));
  }

  private static boolean positive(Object value) {
    if (value instanceof Number) return ((Number) value).longValue() > 0L;
    try {
      return Long.parseLong(String.valueOf(value)) > 0L;
    } catch (Exception ignored) {
      return false;
    }
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
          LOGGER.info(
              "ReadBeforeEdit blocked tool="
                  + invocation.tool().spec().name()
                  + " path="
                  + canonical
                  + " reason=no_returned_read_evidence"
                  + " runId="
                  + activeRunId
                  + " evidenceCount="
                  + readPaths.size());
          callback.resolve(
              ToolPolicyDecision.error(
                  "read_evidence_required",
                  "当前任务尚未获得该文件的实际读取内容，暂时无法修改："
                      + canonical
                      + "。若该文件在批量读取中被截断，请先单独读取该文件。",
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

  private void collectResultPaths(Map<String, Object> data, Set<String> output) {
    if (data == null) return;
    for (String key : new String[] {"path", "resolved_path"}) {
      Object value = data.get(key);
      if (value != null) add(String.valueOf(value), output);
    }
    for (String key : new String[] {"read_paths", "paths"}) {
      Object paths = data.get(key);
      if (paths instanceof List) {
        for (Object value : (List<?>) paths) {
          if (value != null) add(String.valueOf(value), output);
        }
      }
    }
    Object items = data.get("items");
    if (items instanceof List) {
      for (Object value : (List<?>) items) {
        if (!(value instanceof Map)) continue;
        Object result = ((Map<?, ?>) value).get("result");
        if (!(result instanceof Map)) continue;
        addResultPath((Map<?, ?>) result, "resolved_path", output);
        addResultPath((Map<?, ?>) result, "path", output);
      }
    }
  }

  private void collectWritePaths(
      ToolInvocation invocation, Map<String, Object> data, Set<String> output) {
    Object resolved = data == null ? null : data.get("resolved_path");
    if (resolved == null && data != null) resolved = data.get("path");
    if (resolved == null && invocation != null) resolved = invocation.arguments().get("path");
    if (resolved != null) add(String.valueOf(resolved), output);
  }

  private void addResultPath(Map<?, ?> result, String key, Set<String> output) {
    Object value = result.get(key);
    if (value != null) add(String.valueOf(value), output);
  }

  private void add(String path, Set<String> output) {
    if (path == null || path.trim().isEmpty()) return;
    try {
      File resolved = workspace.resolveSafely(path);
      if (!resolved.exists() || !resolved.isFile()) return;
      output.add(resolved.getCanonicalPath());
    } catch (Exception ignored) {
      // Invalid paths remain real tool errors and never become read evidence.
    }
  }
}
