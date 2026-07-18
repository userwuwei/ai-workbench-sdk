package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class ListDirTool extends AbstractFileTool {
  public ListDirTool(WorkspaceAccess w) {
    super(w);
  }

  public ToolSpec spec() {
    return new ToolSpec(
        "list_dir", "列出工作区目录", ToolSchemas.object(new String[][] {{"path", "string", "相对路径"}}));
  }

  ToolResult run(ToolArguments a) throws Exception {
    Map<String, Object> d = new LinkedHashMap<>();
    String p = a.getString("path", ".");
    d.put("path", p);
    d.put("entries", workspace.list(p));
    return ToolResult.success(d);
  }
}
