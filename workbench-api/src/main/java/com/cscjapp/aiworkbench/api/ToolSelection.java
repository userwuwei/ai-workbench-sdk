package com.cscjapp.aiworkbench.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered model-visible tool selection returned by a {@link ToolPolicy}. */
public final class ToolSelection {
  private final List<ToolSpec> tools;

  private ToolSelection(List<ToolSpec> tools) {
    this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
  }

  public static ToolSelection all(List<ToolSpec> tools) {
    return new ToolSelection(safe(tools));
  }

  /** Retains the requested names in their original registry order. Unknown names are ignored. */
  public static ToolSelection onlyNames(List<ToolSpec> tools, Collection<String> names) {
    Set<String> requested = names == null
        ? Collections.emptySet()
        : new LinkedHashSet<>(names);
    List<ToolSpec> selected = new ArrayList<>();
    for (ToolSpec tool : safe(tools)) {
      if (tool != null && requested.contains(tool.name())) selected.add(tool);
    }
    return new ToolSelection(selected);
  }

  public List<ToolSpec> tools() {
    return tools;
  }

  private static List<ToolSpec> safe(List<ToolSpec> tools) {
    return tools == null ? Collections.emptyList() : tools;
  }
}
