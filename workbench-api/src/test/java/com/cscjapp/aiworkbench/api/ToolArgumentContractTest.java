package com.cscjapp.aiworkbench.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class ToolArgumentContractTest {
  @Test
  public void legacyConstructorsRemainBestEffortAndNonStrict() {
    ToolSpec legacyTool =
        new ToolSpec("legacy", "", Collections.singletonMap("type", "object"));
    ModelEndpoint legacyEndpoint =
        new ModelEndpoint("https://example.test/v1", "", "model", 0.2, true, false);

    assertFalse(legacyTool.strictSchema());
    assertEquals(ToolArgumentMode.BEST_EFFORT, legacyEndpoint.toolArgumentMode());
    assertTrue(legacyEndpoint.namedToolChoiceSupported());
  }

  @Test
  public void explicitStrictContractsAreRetained() {
    ToolSpec strictTool =
        new ToolSpec("strict", "", Collections.singletonMap("type", "object"), true);
    ModelEndpoint strictEndpoint =
        new ModelEndpoint(
            "https://example.test/v1",
            "",
            "model",
            0.2,
            true,
            false,
            ToolArgumentMode.STRICT);

    assertTrue(strictTool.strictSchema());
    assertEquals(ToolArgumentMode.STRICT, strictEndpoint.toolArgumentMode());
  }

  @Test
  public void endpointCanDisableNamedToolChoiceWithoutBreakingLegacyDefaults() {
    ModelEndpoint endpoint =
        new ModelEndpoint(
            "https://example.test/v1",
            "",
            "deepseek-chat",
            0.2,
            true,
            false,
            false,
            ignored -> Collections.emptyMap(),
            ToolArgumentMode.BEST_EFFORT);

    assertFalse(endpoint.namedToolChoiceSupported());
  }
}
