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
import java.io.FileInputStream;
import java.security.MessageDigest;
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
  private final Map<String, ReadEvidence> readEvidence = new LinkedHashMap<>();
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
    readEvidence.clear();
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
      collectReadEvidence(invocation, result.data());
    } else if ((completedRole == CodeToolRole.CREATE || completedRole == CodeToolRole.EDIT)
        && writeMayHaveChanged(result)) {
      Set<String> changedPaths = new LinkedHashSet<>();
      collectWritePaths(invocation, result.data(), changedPaths);
      for (String path : changedPaths) readEvidence.remove(path);
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
      readEvidence.clear();
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
        ReadEvidence evidence = readEvidence.get(canonical);
        String currentRevision = sha256(target);
        if (evidence != null && currentRevision.equals(evidence.revision)) {
          callback.resolve(ToolPolicyDecision.proceed(invocation.arguments()));
        } else {
          if (evidence != null) readEvidence.remove(canonical);
          LOGGER.info(
              "ReadBeforeEdit blocked tool="
                  + invocation.tool().spec().name()
                  + " path="
                  + canonical
                  + " reason=no_current_revision_read_evidence"
                  + " runId="
                  + activeRunId
                  + " evidenceCount="
                  + readEvidence.size());
          callback.resolve(
              ToolPolicyDecision.error(
                  "read_evidence_required",
                  "当前任务尚未获得该文件当前 revision 的真实源码证据，暂时无法修改："
                      + canonical
                      + "。跨区域修复请先使用 read_plan 声明阅读目的和证据需求；单一区域修改必须返回实际 content。",
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

  private synchronized void collectReadEvidence(
      ToolInvocation invocation, Map<String, Object> data) {
    if (invocation == null || data == null) return;
    String toolName = invocation.tool().spec().name();
    if ("read_plan".equals(toolName)) {
      if (!readyReadPlan(data) || !nonEmptyList(data.get("evidence"))) return;
      String path = text(first(data, "resolved_path", "path"));
      if (path.isEmpty()) path = invocation.arguments().getString("path", "");
      addRevisionEvidence(path, text(data.get("revision")), true);
      return;
    }
    if (data.containsKey("content")) {
      String path = text(first(data, "resolved_path", "path"));
      if (path.isEmpty()) path = invocation.arguments().getString("path", "");
      addRevisionEvidence(path, "", false);
    }
    Object items = data.get("items");
    if (!(items instanceof List)) return;
    for (Object value : (List<?>) items) {
      if (!(value instanceof Map)) continue;
      Object nested = ((Map<?, ?>) value).get("result");
      if (!(nested instanceof Map) || !((Map<?, ?>) nested).containsKey("content")) continue;
      Map<?, ?> result = (Map<?, ?>) nested;
      addRevisionEvidence(text(first(result, "resolved_path", "path")), "", false);
    }
  }

  private static boolean readyReadPlan(Map<String, Object> data) {
    Object raw = data.get("coverage_summary");
    if (!(raw instanceof Map)) return false;
    return Boolean.TRUE.equals(((Map<?, ?>) raw).get("ready_for_edit"));
  }

  private static boolean nonEmptyList(Object value) {
    return value instanceof List && !((List<?>) value).isEmpty();
  }

  private void addRevisionEvidence(String path, String expectedRevision, boolean planned) {
    if (path == null || path.trim().isEmpty()) return;
    try {
      File resolved = workspace.resolveSafely(path);
      if (!resolved.exists() || !resolved.isFile()) return;
      String actualRevision = sha256(resolved);
      if (!expectedRevision.isEmpty() && !actualRevision.equalsIgnoreCase(expectedRevision)) return;
      readEvidence.put(
          resolved.getCanonicalPath(),
          new ReadEvidence(actualRevision, planned));
    } catch (Exception ignored) {
      // Invalid or concurrently replaced paths never become edit evidence.
    }
  }

  private void collectWritePaths(
      ToolInvocation invocation, Map<String, Object> data, Set<String> output) {
    Object resolved = data == null ? null : data.get("resolved_path");
    if (resolved == null && data != null) resolved = data.get("path");
    if (resolved == null && invocation != null) resolved = invocation.arguments().get("path");
    if (resolved != null) add(String.valueOf(resolved), output);
  }

  private static Object first(Map<?, ?> source, String... keys) {
    if (source == null) return null;
    for (String key : keys) {
      Object value = source.get(key);
      if (value != null) return value;
    }
    return null;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String sha256(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (FileInputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
    }
    StringBuilder result = new StringBuilder();
    for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
    return result.toString();
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

  private static final class ReadEvidence {
    final String revision;
    final boolean planned;

    ReadEvidence(String revision, boolean planned) {
      this.revision = revision;
      this.planned = planned;
    }
  }
}
