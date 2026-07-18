package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import androidx.lifecycle.ViewModel;
import org.junit.Test;

public final class WorkbenchViewModelFactoryTest {
  @Test
  public void createsPackagePrivateWorkbenchViewModelWithoutReflection() {
    WorkbenchViewModel viewModel =
        WorkbenchViewModelFactory.INSTANCE.create(WorkbenchViewModel.class);

    assertNotNull(viewModel);
  }

  @Test
  public void rejectsUnknownViewModelTypes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkbenchViewModelFactory.INSTANCE.create(UnknownViewModel.class));
  }

  private static final class UnknownViewModel extends ViewModel {}
}
