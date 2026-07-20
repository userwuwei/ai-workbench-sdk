package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class CreateFileTool extends AbstractFileTool {
  public CreateFileTool(WorkspaceAccess w) {
    super(w);
  }

  public ToolSpec spec() {
    return new ToolSpec(
        "create_file",
        "创建尚不存在的新文件；仅运行时明确授权的预创建入口允许例外，其他已有文件必须使用 search_replace",
        ToolSchemas.object(
            new String[][] {
              {"path", "string", "目标路径"},
              {"content", "string", "新文件的完整内容"}
            },
            "path",
            "content"));
  }

  ToolResult run(ToolArguments a) throws Exception {
    String requested = a.getString("__requested_path", a.getString("path", ""));
    String resolved = a.getString("path", "");
    boolean existed = workspace.exists(resolved);
    boolean overwrite = a.getBoolean("overwrite", false);
    workspace.writeAtomic(resolved, a.getString("content", ""), overwrite);
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("requested_path", requested);
    d.put("resolved_path", resolved);
    d.put("path", resolved);
    d.put("conflict_resolution", a.getString("__conflict_resolution", "none"));
    d.put("created", !existed);
    d.put("overwritten", existed && overwrite);
    return ToolResult.success(d);
  }
}
