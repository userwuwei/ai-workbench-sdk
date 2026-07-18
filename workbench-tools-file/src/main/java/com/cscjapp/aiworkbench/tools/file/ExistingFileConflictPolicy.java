package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ExistingFileConflictPolicy implements ToolPolicy {
  private final WorkspaceAccess workspace;

  public ExistingFileConflictPolicy(WorkspaceAccess workspace) {
    this.workspace = workspace;
  }

  public boolean supports(ToolInvocation i) {
    return "create_file".equals(i.tool().spec().name());
  }

  public Cancellable evaluate(
      ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicReference<Cancellable> active = new AtomicReference<>(Cancellable.NONE);
    context
        .backgroundExecutor()
        .execute(
            () -> {
              try {
                String path = invocation.arguments().getString("path", "");
                workspace.resolveSafely(path);
                if (cancelled.get()) return;
                if (!workspace.exists(path) || workspace.isDirectory(path)) {
                  callback.resolve(ToolPolicyDecision.proceed(invocation.arguments()));
                  return;
                }
                List<UserDecisionRequest.Option> options =
                    Arrays.asList(
                        new UserDecisionRequest.Option("overwrite", "覆盖当前文件"),
                        new UserDecisionRequest.Option("create_new", "新建文件"));
                UserDecisionRequest request =
                    new UserDecisionRequest("文件已存在", path + " 已存在，请选择写入方式", options, false);
                Cancellable decision =
                    context
                        .userDecisions()
                        .request(
                            request,
                            new UserDecisionService.Callback() {
                              public void onDecision(String option) {
                                if (cancelled.get()) return;
                                if ("overwrite".equals(option)) {
                                  callback.resolve(
                                      ToolPolicyDecision.proceed(
                                          invocation
                                              .arguments()
                                              .with("__requested_path", path)
                                              .with("__conflict_resolution", "overwrite")
                                              .with("overwrite", true)));
                                  return;
                                }
                                if ("create_new".equals(option)) {
                                  try {
                                    String unique = uniquePath(path);
                                    callback.resolve(
                                        ToolPolicyDecision.proceed(
                                            invocation
                                                .arguments()
                                                .with("__requested_path", path)
                                                .with("__conflict_resolution", "create_new")
                                                .with("path", unique)
                                                .with("overwrite", false)));
                                  } catch (Exception e) {
                                    callback.resolve(
                                        ToolPolicyDecision.error(
                                            "file_conflict_resolution_failed", e.getMessage()));
                                  }
                                } else callback.resolve(ToolPolicyDecision.cancel("用户取消文件写入"));
                              }

                              public void onCancelled() {
                                if (!cancelled.get())
                                  callback.resolve(ToolPolicyDecision.cancel("用户取消文件写入"));
                              }
                            });
                active.set(decision == null ? Cancellable.NONE : decision);
                if (cancelled.get()) active.get().cancel();
              } catch (Exception e) {
                if (!cancelled.get())
                  callback.resolve(ToolPolicyDecision.error("invalid_target_path", e.getMessage()));
              }
            });
    return () -> {
      if (cancelled.compareAndSet(false, true)) active.get().cancel();
    };
  }

  String uniquePath(String path) throws IOException {
    String normalized = path.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    String dir = slash >= 0 ? normalized.substring(0, slash + 1) : "";
    String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    int dot = name.lastIndexOf('.');
    String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
    for (int i = 1; i < Integer.MAX_VALUE; i++) {
      String candidate = dir + stem + "-" + i + ext;
      if (!workspace.exists(candidate)) return candidate;
    }
    throw new IOException("cannot_allocate_unique_name");
  }
}
