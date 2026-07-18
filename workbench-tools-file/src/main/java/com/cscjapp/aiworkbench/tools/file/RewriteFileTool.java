package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class RewriteFileTool extends AbstractFileTool {
  public RewriteFileTool(WorkspaceAccess w) {
    super(w);
  }

  public ToolSpec spec() {
    return new ToolSpec(
        "rewrite",
        "使用完整内容重写已有文件",
        ToolSchemas.object(
            new String[][] {{"path", "string", "目标路径"}, {"content", "string", "完整内容"}},
            "path",
            "content"));
  }

  ToolResult run(ToolArguments a) throws Exception {
    String p = a.getString("path", "");
    workspace.writeAtomic(p, a.getString("content", ""), true);
    return ToolResult.success(Collections.singletonMap("path", p));
  }
}
