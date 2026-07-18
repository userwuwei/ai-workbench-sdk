package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.concurrent.atomic.AtomicBoolean;

abstract class AbstractFileTool implements AgentTool {
  final WorkspaceAccess workspace;

  AbstractFileTool(WorkspaceAccess w) {
    workspace = w;
  }

  public Cancellable execute(ToolContext context, ToolArguments args, ToolCallback callback) {
    AtomicBoolean cancelled = new AtomicBoolean();
    context
        .backgroundExecutor()
        .execute(
            () -> {
              if (cancelled.get()) return;
              try {
                callback.onComplete(run(args));
              } catch (Exception e) {
                callback.onComplete(ToolResult.error(code(e), e.getMessage(), false));
              }
            });
    return () -> cancelled.set(true);
  }

  abstract ToolResult run(ToolArguments args) throws Exception;

  private static String code(Exception e) {
    String m = e.getMessage();
    return m != null && m.matches("[a-z_]+") ? m : "file_tool_error";
  }
}
