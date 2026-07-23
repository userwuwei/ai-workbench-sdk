package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WorkbenchStreamUiSnapshotTest {
  @Test
  public void captureDoesNotFollowLaterMutableItemChanges() {
    WorkbenchUiItem item = WorkbenchUiItem.reason("模型输出", "模型响应中");
    item.showProgressCounter = true;
    item.progressCounterLabel = "已接收";
    item.progressCounterValue = 45L;
    item.waitingEffect = WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA;
    item.waitingEffectStartedAtMs = 100L;

    WorkbenchStreamUiSnapshot snapshot = WorkbenchStreamUiSnapshot.capture(item);
    item.content = "工具执行完成";
    item.showProgressCounter = false;
    item.progressCounterValue = 0L;

    assertEquals("模型响应中", snapshot.content);
    assertTrue(snapshot.showProgressCounter);
    assertEquals("已接收", snapshot.progressCounterLabel);
    assertEquals(45L, snapshot.progressCounterValue);
    assertTrue(snapshot.auraActive);
  }

  @Test
  public void idleCopyKeepsCounterAndOnlyChangesMessage() {
    WorkbenchUiItem item = WorkbenchUiItem.reason("模型输出", "模型响应中");
    item.showProgressCounter = true;
    item.progressCounterLabel = "已思考";
    item.progressCounterValue = 88L;

    WorkbenchStreamUiSnapshot waiting =
        WorkbenchStreamUiSnapshot.capture(item)
            .withContent("模型仍在处理 · 已等待 10 秒");

    assertEquals("模型仍在处理 · 已等待 10 秒", waiting.content);
    assertEquals("已思考", waiting.progressCounterLabel);
    assertEquals(88L, waiting.progressCounterValue);
  }
}
