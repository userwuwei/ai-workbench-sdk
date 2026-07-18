package com.cscjapp.aiworkbench.api;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public final class WorkbenchUiStateTest {
  @Test
  public void contextIdentityAndDisplayLabelStaySeparate() {
    WorkbenchUiState state = WorkbenchUiState.builder()
        .contextItems(Arrays.asList(
            new WorkbenchContextItem("/project/src/index.html", "src/index.html"),
            new WorkbenchContextItem("/project/src/app.js", "src/app.js")))
        .build();

    assertEquals("/project/src/index.html", state.contextItems().get(0).id());
    assertEquals("src/index.html", state.contextItems().get(0).label());
    assertEquals(Arrays.asList("src/index.html", "src/app.js"), state.contextLabels());
  }
}
