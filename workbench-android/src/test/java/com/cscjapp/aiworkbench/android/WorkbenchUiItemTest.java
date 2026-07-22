package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class WorkbenchUiItemTest {
  @Test
  public void richPresentationStateRestoresWithoutRuntimeAnimationState() {
    WorkbenchUiItem source = WorkbenchUiItem.browserTest(
        "浏览器测试", "通过", Arrays.asList("success::加载", "running::布局审计"));
    source.summaryIconResId = 17;
    source.errorState = true;
    source.statusLevel = WorkbenchUiItem.STATUS_WARNING;
    source.waitingEffect = WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA;
    source.codeBlock = "const x = 1";
    source.codeExpanded = true;
    source.thoughtContentExpanded = true;
    source.contentExpanded = true;
    source.detailExpanded = true;
    source.detailExpandable = true;
    source.showProgressCounter = true;
    source.progressCounterValue = 1234;
    source.progressCounterLabel = "已接收";
    source.detailContent = "真实工具结果";
    source.actionVisible = true;
    source.actionEnabled = false;
    source.actionText = "继续";
    source.actionId = "continue";
    source.diffVisible = true;
    source.diffExpanded = true;
    source.diffTitle = "index.html";
    source.diffMeta = "2 处精确替换";
    source.diffText = "-old\n+new";
    source.browserTestStatus = "error";
    source.browserTestMeta = "screenshot.png";

    WorkbenchUiItem restored = WorkbenchUiItem.from(source.toMap());

    assertNotNull(restored);
    assertEquals(source.type, restored.type);
    assertEquals(source.summaryIconResId, restored.summaryIconResId);
    assertEquals(source.statusLevel, restored.statusLevel);
    assertEquals(WorkbenchUiItem.WAITING_EFFECT_NONE, restored.waitingEffect);
    assertEquals(source.steps, restored.steps);
    assertTrue(restored.codeExpanded);
    assertTrue(restored.thoughtContentExpanded);
    assertTrue(restored.contentExpanded);
    assertTrue(restored.detailExpanded);
    assertTrue(restored.detailExpandable);
    assertFalse(restored.showProgressCounter);
    assertEquals(0, restored.progressCounterValue);
    assertEquals("", restored.progressCounterLabel);
    assertEquals(source.detailContent, restored.detailContent);
    assertTrue(restored.actionVisible);
    assertFalse(restored.actionEnabled);
    assertEquals(source.actionId, restored.actionId);
    assertTrue(restored.diffVisible);
    assertTrue(restored.diffExpanded);
    assertEquals(source.diffText, restored.diffText);
    assertEquals(source.browserTestStatus, restored.browserTestStatus);
    assertEquals(source.browserTestMeta, restored.browserTestMeta);
  }

  @Test
  public void legacyStringTypesStillRestoreToReferenceCardTypes() {
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("type", "user");
    user.put("title", "你");
    Map<String, Object> tool = new LinkedHashMap<>();
    tool.put("type", "tool");

    assertEquals(WorkbenchUiItem.TYPE_USER_DEMAND, WorkbenchUiItem.from(user).type);
    assertEquals(WorkbenchUiItem.TYPE_EDIT_NOTICE, WorkbenchUiItem.from(tool).type);
  }

  @Test
  public void fullStreamContentPersistsEvenWhenUiSnapshotIsRateLimited() {
    WorkbenchUiItem item = WorkbenchUiItem.reason("模型输出", "界面快照");
    item.appendStreamContent("完整");
    item.appendStreamContent("正文");
    item.appendStreamCodeBlock("完整思考");

    WorkbenchUiItem restored = WorkbenchUiItem.from(item.toMap());

    assertEquals("完整正文", restored.content);
    assertEquals("完整思考", restored.codeBlock);
  }

  @Test
  public void allSevenReferenceCardTypesRemainStable() {
    WorkbenchUiItem plan = WorkbenchUiItem.plan("p", Arrays.asList("s"));
    assertEquals(1, plan.getItemType());
    assertTrue(plan.detailExpanded);
    assertEquals(2, WorkbenchUiItem.thought("t", "c", "code").getItemType());
    assertEquals(3, WorkbenchUiItem.summary("t", "c").getItemType());
    assertEquals(4, WorkbenchUiItem.reason("t", "c").getItemType());
    assertEquals(5, WorkbenchUiItem.editNotice("t", "c").getItemType());
    assertEquals(6, WorkbenchUiItem.userDemand("t", "c").getItemType());
    assertEquals(7, WorkbenchUiItem.browserTest("t", "c", Arrays.asList()).getItemType());
  }
}
