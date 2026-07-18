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
        "创建文件；已存在目标由宿主用户决定覆盖或新建",
        ToolSchemas.object(
            new String[][] {
              {"path", "string", "目标路径"},
              {"content", "string", "完整内容"},
              {"overwrite", "boolean", "兼容字段，最终以用户选择为准"}
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
