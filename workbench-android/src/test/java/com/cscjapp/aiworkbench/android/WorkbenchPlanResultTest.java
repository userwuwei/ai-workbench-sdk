package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.ToolResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class WorkbenchPlanResultTest {
  @Test
  public void browserFailureAndLayoutFailureCannotAdvancePlan() {
    assertFalse(
        WorkbenchViewModel.semanticToolPassed(
            "browser_test", ToolResult.success(map("passed", false))));
    assertFalse(
        WorkbenchViewModel.semanticToolPassed(
            "browser_test",
            ToolResult.success(map("passed", true, "layout_audit", map("passed", false)))));
    assertTrue(
        WorkbenchViewModel.semanticToolPassed(
            "browser_test",
            ToolResult.success(map("passed", true, "layout_audit", map("passed", true)))));
  }

  @Test
  public void qualityRequiresPassWithoutBlockersOrMinimumRisk() {
    assertFalse(
        WorkbenchViewModel.semanticToolPassed(
            "quality_review",
            ToolResult.success(
                map(
                    "passed",
                    true,
                    "minimal_version_risk",
                    true,
                    "blocking_gaps",
                    Collections.emptyList()))));
    assertFalse(
        WorkbenchViewModel.semanticToolPassed(
            "quality_review",
            ToolResult.success(
                map(
                    "passed",
                    true,
                    "minimal_version_risk",
                    false,
                    "blocking_gaps",
                    Collections.singletonList("gap")))));
    assertTrue(
        WorkbenchViewModel.semanticToolPassed(
            "quality_review",
            ToolResult.success(
                map(
                    "passed",
                    true,
                    "minimal_version_risk",
                    false,
                    "blocking_gaps",
                    Collections.emptyList(),
                    "claimed_but_unsupported",
                    Collections.emptyList()))));
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      result.put(String.valueOf(values[i]), values[i + 1]);
    }
    return result;
  }
}
