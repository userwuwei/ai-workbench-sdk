package com.cscjapp.aiworkbench.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps persisted/request history protocol-valid and bounded. */
public final class AgentHistory {
  static final String COMPLETED_TASK_HISTORY_PREFIX =
      "已完成项目任务摘要（仅用于连续性，不代表当前工具证据；修改前仍需读取真实文件）：";

  private AgentHistory() {}

  public static List<AgentMessage> sanitize(List<AgentMessage> source) {
    if (source == null || source.isEmpty()) return Collections.emptyList();
    List<AgentMessage> out = new ArrayList<>();
    for (int i = 0; i < source.size(); ) {
      AgentMessage message = source.get(i);
      if (message == null || message.role() == AgentMessage.Role.TOOL) {
        i++;
        continue;
      }
      if (message.role() != AgentMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) {
        out.add(message);
        i++;
        continue;
      }
      Set<String> required = new HashSet<>();
      for (AgentToolCall call : message.toolCalls()) required.add(call.id());
      List<AgentMessage> group = new ArrayList<>();
      group.add(message);
      int cursor = i + 1;
      while (cursor < source.size()
          && source.get(cursor) != null
          && source.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage tool = source.get(cursor);
        if (required.remove(tool.toolCallId())) group.add(tool);
        cursor++;
      }
      // A process death can leave an assistant tool call without every tool result.
      // Drop that trailing incomplete group instead of sending invalid history.
      if (!required.isEmpty()) break;
      out.addAll(group);
      i = cursor;
    }
    return Collections.unmodifiableList(out);
  }

  public static List<AgentMessage> bounded(
      List<AgentMessage> source, int maxMessages, int maxChars) {
    List<AgentMessage> safe = sanitize(source);
    if (safe.isEmpty()) return safe;
    AgentMessage system = safe.get(0).role() == AgentMessage.Role.SYSTEM ? safe.get(0) : null;
    int startFloor = system == null ? 0 : 1;
    int start = safe.size();
    int chars = system == null ? 0 : system.content().length();
    int count = system == null ? 0 : 1;
    while (start > startFloor) {
      AgentMessage candidate = safe.get(start - 1);
      int nextChars = chars + candidate.content().length();
      if (count >= Math.max(2, maxMessages) || nextChars > Math.max(4096, maxChars)) break;
      chars = nextChars;
      count++;
      start--;
    }
    // Avoid beginning the retained conversation with an orphan tool response.
    while (start < safe.size() && safe.get(start).role() == AgentMessage.Role.TOOL) start++;
    int lastUser = -1;
    for (int i = safe.size() - 1; i >= startFloor; i--) {
      if (safe.get(i).role() == AgentMessage.Role.USER) {
        lastUser = i;
        break;
      }
    }
    // The current demand is non-negotiable even if one tool result is larger
    // than the advisory character budget.
    if (lastUser >= 0 && start > lastUser) start = lastUser;
    List<AgentMessage> out = new ArrayList<>();
    if (system != null) out.add(system);
    out.addAll(safe.subList(start, safe.size()));
    return Collections.unmodifiableList(out);
  }

  /** Removes completed tool protocol bodies before a new user task while retaining short outcomes. */
  public static List<AgentMessage> compactCompletedTasks(List<AgentMessage> source) {
    List<AgentMessage> safe = sanitize(source);
    if (!endsWithCompletedFinalize(safe)) return safe;
    AgentMessage system =
        !safe.isEmpty() && safe.get(0).role() == AgentMessage.Role.SYSTEM ? safe.get(0) : null;
    List<String> summaries = new ArrayList<>();
    String pendingDemand = "";
    for (int index = system == null ? 0 : 1; index < safe.size(); index++) {
      AgentMessage message = safe.get(index);
      if (message.role() == AgentMessage.Role.USER) {
        String content = message.content().trim();
        if (content.startsWith(COMPLETED_TASK_HISTORY_PREFIX)) {
          summaries.add(trim(content.substring(COMPLETED_TASK_HISTORY_PREFIX.length()), 6000));
        } else if (pendingDemand.isEmpty() && !internalFeedback(content)) {
          pendingDemand = trim(content, 300);
        }
        continue;
      }
      if (message.role() != AgentMessage.Role.ASSISTANT) continue;
      for (AgentToolCall call : message.toolCalls()) {
        if (!"finalize_task".equals(call.name())) continue;
        summaries.add(summary(pendingDemand, call.arguments()));
        pendingDemand = "";
      }
    }
    String joined = joinSummaries(summaries, 10000);
    if (joined.isEmpty()) return safe;
    List<AgentMessage> result = new ArrayList<>();
    if (system != null) result.add(system);
    result.add(AgentMessage.user(COMPLETED_TASK_HISTORY_PREFIX + "\n" + joined));
    return Collections.unmodifiableList(result);
  }

  private static boolean endsWithCompletedFinalize(List<AgentMessage> messages) {
    if (messages.isEmpty()) return false;
    AgentMessage last = messages.get(messages.size() - 1);
    return last.role() == AgentMessage.Role.TOOL && "finalize_task".equals(last.name());
  }

  private static String summary(String demand, com.cscjapp.aiworkbench.api.ToolArguments args) {
    StringBuilder value = new StringBuilder("- ");
    if (!demand.isEmpty()) value.append("需求：").append(demand).append("；");
    String status = args.getString("status", "completed");
    String result = trim(args.getString("summary", args.getString("content", "")), 700);
    value.append("状态：").append(status);
    if (!result.isEmpty()) value.append("；结果：").append(result);
    appendList(value, "；变更：", args.get("changed_files"), 600);
    appendList(value, "；验证：", args.get("verification"), 800);
    return value.toString();
  }

  private static void appendList(StringBuilder target, String label, Object raw, int max) {
    if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) return;
    StringBuilder value = new StringBuilder();
    for (Object item : (List<?>) raw) {
      if (item == null) continue;
      if (value.length() > 0) value.append("、");
      value.append(String.valueOf(item));
    }
    if (value.length() > 0) target.append(label).append(trim(value.toString(), max));
  }

  private static boolean internalFeedback(String content) {
    return content.startsWith("验证未通过")
        || content.startsWith("请调用已注册的终态工具")
        || content.startsWith(COMPLETED_TASK_HISTORY_PREFIX);
  }

  private static String joinSummaries(List<String> source, int maxChars) {
    StringBuilder value = new StringBuilder();
    for (int index = Math.max(0, source.size() - 8); index < source.size(); index++) {
      String item = source.get(index) == null ? "" : source.get(index).trim();
      if (item.isEmpty()) continue;
      if (value.length() > 0) value.append('\n');
      value.append(item);
    }
    if (value.length() <= maxChars) return value.toString();
    return value.substring(value.length() - maxChars);
  }

  private static String trim(String value, int max) {
    String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
    return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
  }
}
