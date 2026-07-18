package com.cscjapp.aiworkbench.api;

import java.util.Collections;
import java.util.List;

/** App-provided customization surface. Core orchestration remains language agnostic. */
public interface WorkbenchDefinition {
  String id();

  String displayName();

  List<PromptContributor> promptContributors();

  List<ContextProvider> contextProviders();

  List<AgentTool> tools();

  List<ToolPolicy> toolPolicies();

  List<TaskValidator> validators();

  WorkbenchHost host();

  /** Dynamic labels/capabilities consumed by the SDK-owned reference UI. */
  default WorkbenchUiStateProvider uiStateProvider() {
    return (request, endpoint) -> WorkbenchUiState.builder()
        .projectName(displayName())
        .modelLabel(endpoint == null ? "默认模型" : endpoint.modelId())
        .deepThinkingSupported(endpoint != null && endpoint.deepThinking())
        .build();
  }

  /** Optional host and prompt actions rendered by the generic Android workbench. */
  default List<WorkbenchAction> actions() {
    return Collections.emptyList();
  }
}
