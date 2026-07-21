package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class WorkbenchPlanTrackerTest {
  @Test
  public void legacyActionDoesNotDisplayNumericStep() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("step", "1");
    raw.put("action", "完成核心实现");
    WorkbenchPlanTracker tracker = new WorkbenchPlanTracker();
    tracker.load(Collections.singletonList(raw));
    assertEquals(Collections.singletonList("完成核心实现"), tracker.labels());
  }

  @Test
  public void requiredToolsNeedAllRealSuccesses() {
    WorkbenchPlanTracker tracker = new WorkbenchPlanTracker();
    tracker.load(Collections.singletonList(step("verify", "真实验证", "verify",
        Arrays.asList("syntax_check", "browser_test"))));

    assertTrue(tracker.recordTool("syntax_check"));
    assertEquals("running", tracker.states().get(0));
    assertTrue(tracker.recordTool("browser_test"));
    assertEquals("done", tracker.states().get(0));
  }

  @Test
  public void laterEvidenceDoesNotCompleteEarlierUnrelatedStep() {
    WorkbenchPlanTracker tracker = new WorkbenchPlanTracker();
    tracker.load(Arrays.asList(
        step("implement", "实现", "implement", Collections.singletonList("search_replace")),
        step("verify", "验证", "verify", Collections.singletonList("syntax_check"))));

    tracker.recordTool("syntax_check");

    assertEquals("running", tracker.states().get(0));
    assertEquals("done", tracker.states().get(1));
  }

  @Test
  public void finalizeToolNeverCompletesPlanAndHistoryIsInactive() {
    WorkbenchPlanTracker tracker = new WorkbenchPlanTracker();
    tracker.load(Collections.singletonList(
        step("finalize", "结束任务", "finalize", Collections.singletonList("finalize_task"))));

    assertFalse(tracker.recordTool("finalize_task"));
    assertEquals("running", tracker.states().get(0));
    tracker.restore(tracker.snapshot());
    assertEquals("pending", tracker.states().get(0));
  }

  @Test
  public void legacyHistoryCannotRestoreRunningState() {
    WorkbenchPlanTracker tracker = new WorkbenchPlanTracker();
    tracker.restoreLegacy(
        Arrays.asList("旧实现", "旧验证"), Arrays.asList("done", "running"));
    assertEquals(Arrays.asList("done", "pending"), tracker.states());
  }

  private static Map<String, Object> step(
      String id, String title, String phase, List<String> tools) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", id);
    value.put("title", title);
    value.put("phase", phase);
    value.put("required_tools", tools);
    value.put("status", "pending");
    return value;
  }
}
