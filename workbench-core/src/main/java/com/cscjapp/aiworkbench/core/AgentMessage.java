package com.cscjapp.aiworkbench.core;

import java.util.*;

public final class AgentMessage {
  public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
  }

  private final Role role;
  private final String content, name, toolCallId;
  private final List<AgentToolCall> toolCalls;

  private AgentMessage(
      Role role, String content, String name, String toolCallId, List<AgentToolCall> calls) {
    this.role = role;
    this.content = content == null ? "" : content;
    this.name = name == null ? "" : name;
    this.toolCallId = toolCallId == null ? "" : toolCallId;
    this.toolCalls =
        calls == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(calls));
  }

  public static AgentMessage system(String v) {
    return new AgentMessage(Role.SYSTEM, v, "", "", null);
  }

  public static AgentMessage user(String v) {
    return new AgentMessage(Role.USER, v, "", "", null);
  }

  public static AgentMessage assistant(String v, List<AgentToolCall> c) {
    return new AgentMessage(Role.ASSISTANT, v, "", "", c);
  }

  public static AgentMessage tool(String id, String name, String v) {
    return new AgentMessage(Role.TOOL, v, name, id, null);
  }

  public Role role() {
    return role;
  }

  public String content() {
    return content;
  }

  public String name() {
    return name;
  }

  public String toolCallId() {
    return toolCallId;
  }

  public List<AgentToolCall> toolCalls() {
    return toolCalls;
  }
}
