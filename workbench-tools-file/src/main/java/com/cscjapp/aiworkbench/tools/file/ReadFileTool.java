package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class ReadFileTool extends AbstractFileTool {
  public ReadFileTool(WorkspaceAccess w) {
    super(w);
  }

  public ToolSpec spec() {
    return new ToolSpec(
        "read_file",
        "读取完整文本文件",
        ToolSchemas.object(new String[][] {{"path", "string", "相对路径"}}, "path"));
  }

  ToolResult run(ToolArguments a) throws Exception {
    String p = a.getString("path", "");
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("path", p);
    d.put("content", workspace.read(p));
    return ToolResult.success(d);
  }
}
