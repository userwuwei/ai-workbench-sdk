package com.cscjapp.aiworkbench.sample;

import android.content.Intent;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.WorkbenchEvent;
import com.cscjapp.aiworkbench.api.WorkbenchHost;
import com.kongzue.dialogx.dialogs.BottomMenu;
import com.kongzue.dialogx.dialogs.MessageDialog;
import java.io.File;
import java.util.List;

final class PlaygroundHost implements WorkbenchHost {
  private final PlaygroundRuntime runtime;

  PlaygroundHost(PlaygroundRuntime runtime) {
    this.runtime = runtime;
  }

  @Override
  public void openArtifact(String artifactId) {
    try {
      File file = runtime.workspace().resolveSafely(artifactId);
      Intent intent = runtime.activityIntent(ArtifactPreviewActivity.class);
      intent.putExtra(ArtifactPreviewActivity.EXTRA_PATH, file.getAbsolutePath());
      runtime.application().startActivity(intent);
      runtime.log("host", "openArtifact(" + artifactId + ")");
    } catch (Exception error) {
      showMessage("无法打开产物", error.getMessage());
    }
  }

  @Override
  public void refreshArtifacts() {
    runtime.log("host", "refreshArtifacts，文件数=" + runtime.workspaceFiles().size());
    runtime.notifyChanged();
  }

  @Override
  public void handleAction(String actionId, ToolArguments arguments) {
    runtime.log("host", "handleAction(" + actionId + ")");
    if ("select_model".equals(actionId)) {
      showModelSelection();
      return;
    }
    if ("select_context".equals(actionId)) {
      showContextSelection();
      return;
    }
    if ("remove_context".equals(actionId)) {
      runtime.removeContext(arguments == null ? "" : arguments.getString("artifact_id", ""));
      return;
    }
    if ("clear_context".equals(actionId)) {
      runtime.clearContexts();
      return;
    }
    if ("report".equals(actionId)) {
      runtime.log("report", "模拟举报已提交");
      showMessage("模拟举报已提交", "Playground 已记录当前会话状态；本操作不会上传任何数据。");
      return;
    }
    if ("event_logs".equals(actionId)) {
      showMessage("Playground 事件日志", tail(runtime.logs(), 4000));
      return;
    }
    showMessage(
        "Playground Host Action",
        actionId + "\n" + (arguments == null ? "{}" : arguments.asMap()));
  }

  @Override
  public void onEvent(WorkbenchEvent event) {
    if (event == null) return;
    runtime.log("event", event.type() + " · " + event.message());
    if ("task_completed".equals(event.type()) && !runtime.latestArtifact().isEmpty()) {
      runtime.runOnMain(() -> openArtifact(runtime.latestArtifact()));
    }
  }

  private void showContextSelection() {
    runtime.runOnMain(
        () -> {
          List<String> files = runtime.workspaceFiles();
          if (files.isEmpty()) {
            showMessage("选择上下文", "Playground 工作区没有可选文件");
            return;
          }
          List<String> selected = runtime.selectedContexts();
          String[] labels = new String[files.size()];
          for (int i = 0; i < files.size(); i++) {
            String file = files.get(i);
            labels[i] = (selected.contains(file) ? "✓ " : "○ ") + file;
          }
          BottomMenu.show(
                  "选择 Playground 上下文",
                  labels,
                  (dialog, text, index) -> {
                    runtime.toggleContext(files.get(index));
                    return false;
                  })
              .show();
        });
  }

  private void showModelSelection() {
    runtime.runOnMain(
        () -> {
          String realLabel =
              runtime.hasRealConfiguration()
                  ? "真实模型 · " + runtime.model()
                  : "真实模型 · 需要配置";
          String[] entries = {
            "离线模式 · Native Tools",
            "离线模式 · Legacy JSON",
            realLabel,
            "编辑真实模型配置"
          };
          BottomMenu.show(
                  "选择模型与运行模式",
                  entries,
                  (dialog, text, index) -> {
                    if (index == 0) {
                      runtime.openWorkbench(
                          runtime.application(),
                          PlaygroundRuntime.MODE_OFFLINE,
                          "",
                          PlaygroundRuntime.PROTOCOL_NATIVE);
                    } else if (index == 1) {
                      runtime.openWorkbench(
                          runtime.application(),
                          PlaygroundRuntime.MODE_OFFLINE,
                          "",
                          PlaygroundRuntime.PROTOCOL_LEGACY);
                    } else if (index == 2 && runtime.hasRealConfiguration()) {
                      runtime.openWorkbench(
                          runtime.application(),
                          PlaygroundRuntime.MODE_REAL,
                          "",
                          runtime.nativeTools()
                              ? PlaygroundRuntime.PROTOCOL_NATIVE
                              : PlaygroundRuntime.PROTOCOL_LEGACY);
                    } else {
                      runtime
                          .application()
                          .startActivity(runtime.activityIntent(ModelSettingsActivity.class));
                    }
                    return false;
                  })
              .show();
        });
  }

  private void showMessage(String title, String message) {
    runtime.runOnMain(
        () ->
            MessageDialog.build()
                .setTitle(title)
                .setMessage(message == null || message.isEmpty() ? "无内容" : message)
                .setOkButton("知道了")
                .show());
  }

  private static String tail(String value, int max) {
    if (value == null || value.length() <= max) return value;
    return "…\n" + value.substring(value.length() - max);
  }
}
