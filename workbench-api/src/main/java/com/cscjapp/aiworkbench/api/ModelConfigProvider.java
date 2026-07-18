package com.cscjapp.aiworkbench.api;

public interface ModelConfigProvider {
  ModelEndpoint currentModel(WorkbenchLaunchRequest request);
}
