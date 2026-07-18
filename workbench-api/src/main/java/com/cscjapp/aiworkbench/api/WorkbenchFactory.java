package com.cscjapp.aiworkbench.api;

public interface WorkbenchFactory {
  WorkbenchDefinition create(WorkbenchLaunchRequest request);
}
