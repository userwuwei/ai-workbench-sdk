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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.logging.Logger;

/** Requires current-run read evidence before an existing file is edited. */
public final class ReadBeforeEditPolicy implements ToolPolicy, AgentRunLifecycle {
  private static final Logger LOGGER = Logger.getLogger(ReadBeforeEditPolicy.class.getName());
  private final WorkspaceAccess workspace;
  private final Map<String, CodeToolRole> roles;
  private final Map<String, ReadEvidence> readEvidence = new LinkedHashMap<>();
  private final Set<String> recoveryPendingPaths = new LinkedHashSet<>();
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
    recoveryPendingPaths.clear();
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
      recoveryPendingPaths.removeAll(changedPaths);
    } else if (completedRole == CodeToolRole.EDIT) {
      collectRecoveryFailure(invocation, result);
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
      recoveryPendingPaths.clear();
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
        if (evidence != null
            && currentRevision.equals(evidence.revision)
            && (evidence.planned || validRecoveryAnchors(invocation.arguments(), evidence))) {
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
                      + "。请先使用 ready_for_edit=true 的 read_plan；只有 search_replace 精确匹配失败后，"
                      + "才可用同路径 read_file 的真实短锚点重试。",
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
    if ("read_file".equals(toolName) && data.containsKey("content")) {
      String path = text(first(data, "resolved_path", "path"));
      if (path.isEmpty()) path = invocation.arguments().getString("path", "");
      addRecoveryEvidence(path, text(data.get("content")));
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
          new ReadEvidence(actualRevision, planned, ""));
    } catch (Exception ignored) {
      // Invalid or concurrently replaced paths never become edit evidence.
    }
  }

  private void addRecoveryEvidence(String path, String content) {
    if (path == null || path.trim().isEmpty() || content.isEmpty() || lineCount(content) > 80) return;
    try {
      File resolved = workspace.resolveSafely(path);
      if (!resolved.exists() || !resolved.isFile()) return;
      String canonical = resolved.getCanonicalPath();
      if (!recoveryPendingPaths.contains(canonical)) return;
      readEvidence.put(canonical, new ReadEvidence(sha256(resolved), false, content));
    } catch (Exception ignored) {
      // Invalid or concurrently replaced paths never become recovery evidence.
    }
  }

  private static int lineCount(String content) {
    if (content.isEmpty()) return 0;
    int lines = 1;
    for (int index = 0; index < content.length(); index++) {
      if (content.charAt(index) == '\n') lines++;
    }
    return content.endsWith("\n") ? lines - 1 : lines;
  }

  private void collectRecoveryFailure(ToolInvocation invocation, ToolResult result) {
    if (!recoverableSearchReplaceFailure(invocation, result)) return;
    Set<String> paths = new LinkedHashSet<>();
    collectWritePaths(invocation, result == null ? Collections.emptyMap() : result.data(), paths);
    for (String path : paths) {
      recoveryPendingPaths.add(path);
      readEvidence.remove(path);
    }
  }

  private static boolean recoverableSearchReplaceFailure(
      ToolInvocation invocation, ToolResult result) {
    if (invocation == null
        || invocation.tool() == null
        || !"search_replace".equals(invocation.tool().spec().name())
        || result == null
        || result.isSuccess()) return false;
    String detail = (text(result.errorCode()) + " " + text(result.message())).toLowerCase(Locale.US);
    return detail.contains("search_match_count")
        || detail.contains("no_match")
        || detail.contains("multiple")
        || detail.contains("overlap")
        || detail.contains("brace")
        || detail.contains("batch conflict")
        || detail.contains("batch_conflict");
  }

  private static boolean validRecoveryAnchors(ToolArguments arguments, ReadEvidence evidence) {
    if (evidence.planned || evidence.content.isEmpty()) return false;
    List<String> anchors = replacementAnchors(arguments);
    if (anchors.isEmpty()) return false;
    for (String anchor : anchors) {
      if (anchor.isEmpty() || !evidence.content.contains(anchor)) return false;
    }
    return true;
  }

  private static List<String> replacementAnchors(ToolArguments arguments) {
    List<String> anchors = new ArrayList<>();
    Object raw = arguments.get("replacements");
    if (raw instanceof List) {
      for (Object item : (List<?>) raw) {
        if (!(item instanceof Map)) return Collections.emptyList();
        String old = text(((Map<?, ?>) item).get("old"));
        if (old.isEmpty()) return Collections.emptyList();
        anchors.add(old);
      }
    } else {
      String old = arguments.getString("old", "");
      if (!old.isEmpty()) anchors.add(old);
    }
    return anchors;
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
    final String content;

    ReadEvidence(String revision, boolean planned, String content) {
      this.revision = revision;
      this.planned = planned;
      this.content = content == null ? "" : content;
    }
  }
}
