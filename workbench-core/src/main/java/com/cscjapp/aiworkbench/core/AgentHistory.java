package com.cscjapp.aiworkbench.core;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps persisted/request history protocol-valid and bounded. */
public final class AgentHistory {
  private static final Gson GSON = new Gson();
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
    int floor = system == null ? 0 : 1;
    int messageLimit = Math.max(2, maxMessages);
    int charLimit = Math.max(4096, maxChars);
    List<HistoryGroup> groups = groups(safe, floor);
    int lastUser = -1;
    for (int i = safe.size() - 1; i >= floor; i--) {
      if (safe.get(i).role() == AgentMessage.Role.USER) {
        lastUser = i;
        break;
      }
    }
    int baseChars = system == null ? 0 : estimatedChars(system);
    int baseCount = system == null ? 0 : 1;
    int startGroup = suffixStart(groups, baseChars, baseCount, messageLimit, charLimit, 0);
    boolean includesLastUser = contains(groups, startGroup, lastUser);
    List<AgentMessage> out = new ArrayList<>();
    if (system != null) out.add(system);
    if (lastUser >= floor && !includesLastUser) {
      // The current demand is non-negotiable, but it must not pull an oversized
      // tool protocol body back into the request after budgeting excluded it.
      AgentMessage demand = safe.get(lastUser);
      out.add(demand);
      int mandatoryChars = baseChars + estimatedChars(demand);
      int mandatoryCount = baseCount + 1;
      int suffixFloor = firstGroupAfter(groups, lastUser);
      int suffixStart =
          suffixStart(
              groups, mandatoryChars, mandatoryCount, messageLimit, charLimit, suffixFloor);
      appendGroups(out, safe, groups, suffixStart);
    } else {
      appendGroups(out, safe, groups, startGroup);
    }
    return Collections.unmodifiableList(out);
  }

  /** Creates the bounded, lossy request view without mutating persisted or observable history. */
  static List<AgentMessage> forModelRequest(
      List<AgentMessage> source, int maxMessages, int maxChars) {
    return bounded(AgentHistoryRequestProjection.project(source), maxMessages, maxChars);
  }

  static int estimatedChars(List<AgentMessage> messages) {
    int total = 0;
    if (messages != null) for (AgentMessage message : messages) total += estimatedChars(message);
    return total;
  }

  private static int estimatedChars(AgentMessage message) {
    if (message == null) return 0;
    int chars = message.content().length() + message.name().length() + message.toolCallId().length();
    for (AgentToolCall call : message.toolCalls()) {
      chars += call.id().length() + call.name().length();
      if (call.arguments() != null) chars += GSON.toJson(call.arguments().asMap()).length();
    }
    return chars;
  }

  private static List<HistoryGroup> groups(List<AgentMessage> messages, int start) {
    List<HistoryGroup> groups = new ArrayList<>();
    for (int index = start; index < messages.size(); ) {
      int end = index + 1;
      AgentMessage message = messages.get(index);
      if (message.role() == AgentMessage.Role.ASSISTANT && !message.toolCalls().isEmpty()) {
        while (end < messages.size() && messages.get(end).role() == AgentMessage.Role.TOOL) end++;
      }
      int chars = 0;
      for (int cursor = index; cursor < end; cursor++) chars += estimatedChars(messages.get(cursor));
      groups.add(new HistoryGroup(index, end, chars));
      index = end;
    }
    return groups;
  }

  private static int suffixStart(
      List<HistoryGroup> groups,
      int baseChars,
      int baseCount,
      int messageLimit,
      int charLimit,
      int minimumGroup) {
    int start = groups.size();
    int chars = baseChars;
    int count = baseCount;
    for (int index = groups.size() - 1; index >= minimumGroup; index--) {
      HistoryGroup group = groups.get(index);
      if (count + group.messageCount() > messageLimit || chars + group.chars > charLimit) break;
      chars += group.chars;
      count += group.messageCount();
      start = index;
    }
    return start;
  }

  private static boolean contains(List<HistoryGroup> groups, int startGroup, int messageIndex) {
    if (messageIndex < 0) return false;
    for (int index = Math.max(0, startGroup); index < groups.size(); index++) {
      HistoryGroup group = groups.get(index);
      if (messageIndex >= group.start && messageIndex < group.end) return true;
    }
    return false;
  }

  private static int firstGroupAfter(List<HistoryGroup> groups, int messageIndex) {
    for (int index = 0; index < groups.size(); index++) {
      if (groups.get(index).start > messageIndex) return index;
    }
    return groups.size();
  }

  private static void appendGroups(
      List<AgentMessage> output,
      List<AgentMessage> source,
      List<HistoryGroup> groups,
      int startGroup) {
    for (int index = Math.max(0, startGroup); index < groups.size(); index++) {
      HistoryGroup group = groups.get(index);
      output.addAll(source.subList(group.start, group.end));
    }
  }

  private static final class HistoryGroup {
    final int start;
    final int end;
    final int chars;

    HistoryGroup(int start, int end, int chars) {
      this.start = start;
      this.end = end;
      this.chars = chars;
    }

    int messageCount() {
      return end - start;
    }
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

  /** Builds the history carried across an explicit new-task submission. */
  static List<AgentMessage> prepareForNewTask(List<AgentMessage> source) {
    List<AgentMessage> safe = sanitize(source);
    if (safe.isEmpty()) return safe;
    AgentMessage system =
        safe.get(0).role() == AgentMessage.Role.SYSTEM ? safe.get(0) : null;
    List<String> summaries = new ArrayList<>();
    String pendingDemand = "";
    for (int index = system == null ? 0 : 1; index < safe.size(); index++) {
      AgentMessage message = safe.get(index);
      if (message.role() == AgentMessage.Role.USER) {
        String content = message.content().trim();
        if (content.startsWith(COMPLETED_TASK_HISTORY_PREFIX)) {
          summaries.add(trim(content.substring(COMPLETED_TASK_HISTORY_PREFIX.length()), 6000));
        } else if (!internalFeedback(content)) {
          pendingDemand = trim(content, 300);
        }
        continue;
      }
      if (message.role() != AgentMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) continue;
      Map<String, AgentMessage> results = new LinkedHashMap<>();
      int cursor = index + 1;
      while (cursor < safe.size() && safe.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage result = safe.get(cursor++);
        results.put(result.toolCallId(), result);
      }
      for (AgentToolCall call : message.toolCalls()) {
        if (!successfulCompletedFinalize(call, results.get(call.id()))) continue;
        summaries.add(summary(pendingDemand, call.arguments()));
        pendingDemand = "";
      }
    }
    List<AgentMessage> result = new ArrayList<>();
    if (system != null) result.add(system);
    String joined = joinSummaries(summaries, 10000);
    if (!joined.isEmpty()) {
      result.add(AgentMessage.user(COMPLETED_TASK_HISTORY_PREFIX + "\n" + joined));
    }
    return Collections.unmodifiableList(result);
  }

  private static boolean successfulCompletedFinalize(
      AgentToolCall call, AgentMessage resultMessage) {
    if (call == null
        || resultMessage == null
        || !"finalize_task".equals(call.name())
        || !"completed".equals(call.arguments().getString("status", ""))) return false;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> result =
          GSON.fromJson(resultMessage.content(), Map.class);
      return result != null && "success".equalsIgnoreCase(String.valueOf(result.get("status")));
    } catch (RuntimeException ignored) {
      return false;
    }
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
