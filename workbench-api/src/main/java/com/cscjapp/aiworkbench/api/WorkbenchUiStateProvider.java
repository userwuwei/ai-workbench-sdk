package com.cscjapp.aiworkbench.api;

/** Supplies dynamic, product-specific labels to the SDK-owned reference UI. */
public interface WorkbenchUiStateProvider {
  WorkbenchUiState current(WorkbenchLaunchRequest request, ModelEndpoint endpoint);

  /** Optional push updates for context/model/account labels changed without leaving the Activity. */
  default Cancellable observe(WorkbenchLaunchRequest request, Runnable onChanged) {
    return Cancellable.NONE;
  }
}
