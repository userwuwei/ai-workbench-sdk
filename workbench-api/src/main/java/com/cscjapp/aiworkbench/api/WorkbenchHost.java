package com.cscjapp.aiworkbench.api;

public interface WorkbenchHost {
  void openArtifact(String artifactId);

  void refreshArtifacts();

  void handleAction(String actionId, ToolArguments arguments);

  void onEvent(WorkbenchEvent event);
}
