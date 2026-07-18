package com.cscjapp.aiworkbench.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps persisted/request history protocol-valid and bounded. */
public final class AgentHistory {
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
}
