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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Requires current-run read evidence before an existing file is rewritten wholesale. */
public final class ReadBeforeEditPolicy implements ToolPolicy, AgentRunLifecycle {
  private static final Logger LOGGER = Logger.getLogger(ReadBeforeEditPolicy.class.getName());
  private final WorkspaceAccess workspace;
  private final Map<String, CodeToolRole> roles;
  private final Map<String, ReadEvidence> readEvidence = new LinkedHashMap<>();
  private final boolean guardSearchReplace;
  private long activeRunId = -1L;

  public ReadBeforeEditPolicy(
      WorkspaceAccess workspace, Map<String, CodeToolRole> roles) {
    this(workspace, roles, "rewrite_only");
  }

  public ReadBeforeEditPolicy(
      WorkspaceAccess workspace, Map<String, CodeToolRole> roles, String gateMode) {
    if (workspace == null) throw new IllegalArgumentException("workspace required");
    this.workspace = workspace;
    this.roles = roles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roles);
    guardSearchReplace = "legacy".equalsIgnoreCase(text(gateMode));
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
      // A successful local write is itself current-revision evidence: the model supplied the
      // payload and search_replace/rewrite applied it atomically. Keep later repair edits flowing
      // without forcing a non-destructive read round.
      boolean completeSuccess = result.isSuccess()
          && !Boolean.TRUE.equals(result.data().get("partial_apply"))
          && !positive(result.data().get("failed_count"));
      for (String path : changedPaths) {
        if (completeSuccess) addRevisionEvidence(path, "", true);
        else readEvidence.remove(path);
      }
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
    if (role(invocation) != CodeToolRole.EDIT) return false;
    String toolName = invocation.tool().spec().name();
    // Exact-anchor edits enforce freshness in the file tool itself. Production uses
    // rewrite_only; legacy remains a construction-time rollback mode for new runs.
    return "rewrite".equals(toolName)
        || (guardSearchReplace && "search_replace".equals(toolName));
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
                      + "。单个明确位置请使用一次有界 read_file 获取真实短锚点；"
                      + "多个不连续区域或状态流请使用 read_plan 一次收集证据。"
                      + "不要为确认上下文默认完整读取文件。",
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
      addFileEvidence(path, data);
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
      mergeEvidence(resolved.getCanonicalPath(), actualRevision, planned, "");
    } catch (Exception ignored) {
      // Invalid or concurrently replaced paths never become edit evidence.
    }
  }

  private void addFileEvidence(String path, Map<String, Object> data) {
    Object rawContent = data.get("content");
    String content = rawContent == null ? "" : String.valueOf(rawContent);
    if (path == null || path.trim().isEmpty()) return;
    try {
      File resolved = workspace.resolveSafely(path);
      if (!resolved.exists() || !resolved.isFile()) return;
      String canonical = resolved.getCanonicalPath();
      String actualRevision = sha256(resolved);
      String expectedRevision = text(data.get("revision"));
      if (!expectedRevision.isEmpty() && !actualRevision.equalsIgnoreCase(expectedRevision)) return;
      int totalLines = positiveInt(data.get("total_lines"));
      boolean full = Boolean.TRUE.equals(data.get("full_file"))
          || "full_file".equalsIgnoreCase(text(data.get("mode")))
          || (!Boolean.TRUE.equals(data.get("truncated"))
              && totalLines > 0
              && lineCount(content) >= totalLines);
      if (!full && content.isEmpty()) return;
      mergeEvidence(canonical, actualRevision, full, full ? "" : content);
    } catch (Exception ignored) {
      // Invalid or concurrently replaced paths never become edit evidence.
    }
  }

  private void mergeEvidence(
      String canonical, String revision, boolean planned, String fragment) {
    ReadEvidence previous = readEvidence.get(canonical);
    if (previous == null || !revision.equals(previous.revision)) {
      readEvidence.put(canonical, ReadEvidence.create(revision, planned, fragment));
      return;
    }
    boolean mergedPlanned = previous.planned || planned;
    List<String> fragments = new ArrayList<>();
    if (!mergedPlanned) {
      fragments.addAll(previous.fragments);
      addFragment(fragments, fragment);
    }
    readEvidence.put(canonical, new ReadEvidence(revision, mergedPlanned, fragments));
  }

  private static void addFragment(List<String> fragments, String candidate) {
    if (candidate == null || candidate.isEmpty()) return;
    for (String fragment : fragments) {
      if (fragment.contains(candidate)) return;
    }
    for (int index = fragments.size() - 1; index >= 0; index--) {
      if (candidate.contains(fragments.get(index))) fragments.remove(index);
    }
    fragments.add(candidate);
  }

  private static int positiveInt(Object value) {
    if (value instanceof Number) return ((Number) value).intValue();
    try {
      return Integer.parseInt(text(value));
    } catch (Exception ignored) {
      return -1;
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

  private static boolean validRecoveryAnchors(ToolArguments arguments, ReadEvidence evidence) {
    if (evidence.planned || evidence.fragments.isEmpty()) return false;
    List<String> anchors = replacementAnchors(arguments);
    if (anchors.isEmpty()) return false;
    for (String anchor : anchors) {
      boolean found = false;
      for (String fragment : evidence.fragments) {
        if (fragment.contains(anchor)) {
          found = true;
          break;
        }
      }
      if (!found) return false;
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
    final List<String> fragments;

    ReadEvidence(String revision, boolean planned, List<String> fragments) {
      this.revision = revision;
      this.planned = planned;
      this.fragments = Collections.unmodifiableList(new ArrayList<>(fragments));
    }

    static ReadEvidence create(
        String revision, boolean planned, String fragment) {
      List<String> fragments = new ArrayList<>();
      if (!planned) addFragment(fragments, fragment);
      return new ReadEvidence(revision, planned, fragments);
    }
  }
}
