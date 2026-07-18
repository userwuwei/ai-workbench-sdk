package com.cscjapp.aiworkbench.android;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

/** Creates the SDK-owned ViewModel without relying on Lifecycle reflection. */
final class WorkbenchViewModelFactory implements ViewModelProvider.Factory {
  static final WorkbenchViewModelFactory INSTANCE = new WorkbenchViewModelFactory();

  private WorkbenchViewModelFactory() {}

  @NonNull
  @Override
  public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
    if (!WorkbenchViewModel.class.equals(modelClass)) {
      throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
    }
    return modelClass.cast(new WorkbenchViewModel());
  }
}
