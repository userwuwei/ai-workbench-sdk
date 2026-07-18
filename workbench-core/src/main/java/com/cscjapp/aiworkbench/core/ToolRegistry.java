package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class ToolRegistry {
  private final Map<String, AgentTool> tools = new LinkedHashMap<>();

  public ToolRegistry(List<AgentTool> source) {
    if (source != null) for (AgentTool t : source) register(t);
  }

  public void register(AgentTool tool) {
    if (tool == null || tool.spec() == null) throw new IllegalArgumentException("invalid tool");
    String n = tool.spec().name();
    if (tools.put(n, tool) != null) throw new IllegalStateException("duplicate tool: " + n);
  }

  public AgentTool find(String name) {
    return tools.get(name);
  }

  public boolean hasTerminalTool() {
    for (AgentTool tool : tools.values()) if (tool.requestsFinalize()) return true;
    return false;
  }

  public List<ToolSpec> specs() {
    List<ToolSpec> out = new ArrayList<>();
    for (AgentTool t : tools.values()) out.add(t.spec());
    return Collections.unmodifiableList(out);
  }
}
