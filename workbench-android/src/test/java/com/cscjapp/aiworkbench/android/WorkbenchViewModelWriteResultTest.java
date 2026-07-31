package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class WorkbenchViewModelWriteResultTest {
  @Test
  public void recoverableUnwrittenSearchReplaceUpdatesOnlyReasonAsWarning() throws Exception {
    WorkbenchViewModel viewModel = new WorkbenchViewModel();
    WorkbenchUiItem reason = WorkbenchUiItem.reason("工具结果", "执行中");
    reason.waitingEffect = WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA;
    set(viewModel, "currentReason", reason);
    List<WorkbenchUiItem> state = state(viewModel);
    state.add(reason);
    ToolResult result = ToolResult.error(
        "search_replace_destructive_change",
        "大范围且明显破坏性的替换",
        true,
        Collections.singletonMap("current_file_changed", false));

    renderWriteResult(viewModel, result);

    assertEquals(1, state.size());
    assertEquals(WorkbenchUiItem.TYPE_REASON, state.get(0).type);
    assertEquals("修改尚未写入", reason.title);
    assertEquals(WorkbenchUiItem.STATUS_WARNING, reason.statusLevel);
    assertFalse(reason.errorState);
    assertTrue(reason.content.contains("正在根据当前源码调整修改范围"));
    assertTrue(reason.content.contains("大范围且明显破坏性的替换"));
    assertEquals(WorkbenchUiItem.WAITING_EFFECT_NONE, reason.waitingEffect);
  }

  private static void renderWriteResult(WorkbenchViewModel viewModel, ToolResult result)
      throws Exception {
    Method method = WorkbenchViewModel.class.getDeclaredMethod(
        "renderWriteResult", String.class, ToolArguments.class, ToolResult.class);
    method.setAccessible(true);
    method.invoke(
        viewModel,
        "search_replace",
        new ToolArguments(Collections.singletonMap("path", "/project/src/app.js")),
        result);
  }

  @SuppressWarnings("unchecked")
  private static List<WorkbenchUiItem> state(WorkbenchViewModel viewModel) throws Exception {
    Field field = WorkbenchViewModel.class.getDeclaredField("state");
    field.setAccessible(true);
    return (List<WorkbenchUiItem>) field.get(viewModel);
  }

  private static void set(WorkbenchViewModel viewModel, String name, Object value)
      throws Exception {
    Field field = WorkbenchViewModel.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(viewModel, value);
  }
}
