package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class SearchReplaceTool extends AbstractFileTool {
  public SearchReplaceTool(WorkspaceAccess w) {
    super(w);
  }

  public ToolSpec spec() {
    return new ToolSpec(
        "search_replace",
        "精确替换文件中的唯一文本",
        ToolSchemas.object(
            new String[][] {
              {"path", "string", "目标路径"}, {"old", "string", "旧文本"}, {"new", "string", "新文本"}
            },
            "path",
            "old",
            "new"));
  }

  ToolResult run(ToolArguments a) throws Exception {
    String p = a.getString("path", ""), old = a.getString("old", ""), n = a.getString("new", "");
    String c = workspace.read(p);
    int first = c.indexOf(old);
    if (first < 0) throw new IllegalArgumentException("search_text_not_found");
    if (c.indexOf(old, first + old.length()) >= 0)
      throw new IllegalArgumentException("search_text_not_unique");
    workspace.writeAtomic(p, c.substring(0, first) + n + c.substring(first + old.length()), true);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("path", p);
    d.put("replacements", 1);
    return ToolResult.success(d);
  }
}
