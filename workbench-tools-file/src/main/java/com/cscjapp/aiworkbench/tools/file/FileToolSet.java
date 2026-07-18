package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class FileToolSet {
  private FileToolSet() {}

  public static List<AgentTool> standard(WorkspaceAccess w) {
    return Arrays.asList(
        new ListDirTool(w),
        new ReadFileTool(w),
        new CreateFileTool(w),
        new RewriteFileTool(w),
        new SearchReplaceTool(w));
  }
}
