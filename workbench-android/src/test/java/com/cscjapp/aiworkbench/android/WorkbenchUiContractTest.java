package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.Test;

/** Prevents the shared SDK UI from regressing back to the old minimal shell. */
public final class WorkbenchUiContractTest {
  @Test
  public void activityContainsEveryReferenceWorkbenchRegion() throws Exception {
    String layout = read("src/main/res/layout/aiw_activity_workbench.xml");
    for (String id : Arrays.asList(
        "aiw_topBar", "aiw_contextUsageChip", "aiw_rvPlan", "aiw_rvWorkbench",
        "aiw_toolRunningContainer", "aiw_tvWorkbenchNewUpdates", "aiw_rv_context_files",
        "aiw_bottomInput", "aiw_btnDeepThinking", "aiw_btnContextQuick",
        "aiw_btnExplainCode", "aiw_btnSend")) {
      assertTrue("missing reference region " + id, layout.contains("@+id/" + id));
    }
  }

  @Test
  public void allReferenceTimelineCardsAndAnimationWidgetsExist() {
    for (String file : Arrays.asList(
        "aiw_item_plan.xml", "aiw_item_thought.xml", "aiw_item_summary.xml",
        "aiw_item_user_demand.xml", "aiw_item_browser_test.xml",
        "aiw_item_tool_running.xml", "aiw_item_context_file.xml")) {
      assertTrue(file, new File("src/main/res/layout", file).isFile());
    }
    for (String file : Arrays.asList(
        "AnimatedDigitView.java", "AnimatedNumberView.java", "DashedCircleLoadingView.java",
        "WorkbenchWaitingAuraView.java", "WorkbenchWaitingAuraLayout.java")) {
      assertTrue(file, new File(
          "src/main/java/com/cscjapp/aiworkbench/android/widget", file).isFile());
    }
  }

  @Test
  public void exactSendStopAssetsAndConsolasFontArePackaged() {
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_send.png",
        "a5b03c4c791bdbe9dd4cc10203bcfe8e7897e1f171001decdcf6b67343c4ad17");
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_stop2.png",
        "7467e3f21c34321da05612d1322ec9600582964fd37ef7d1b16091d0a81a240d");
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_file_edit.png",
        "9b3701128dfc122d80fa2e51e762b7fb8960984759837a0f0068051b1bd8bd86");
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_model.png",
        "502d9e7d4a81fb391cc99b49b109b49297a551d6b002f8fc16c5223224d0c3c3");
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_report.png",
        "560e42970b9c96c7465ac36352b85a3be4a46469636bbfe9c2f98d0eb17ba81d");
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_status_success.png",
        "1e91aa77a2b5d6aa92ab21bb94d7e248b02b961642b516e665d2c7c848586e78");
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_status_warning.png",
        "0701ea7abae4e0bbbdf91f57572c22ca86a7a03eecc31d8a77e654d700b94e62");
    assertSha256("src/main/res/drawable-xxhdpi/aiw_ic_status_error.png",
        "4de8dbe0b348126780ae516576512d526b84250be2fd259e89ae90dc58126b29");
    assertSha256("src/main/assets/fonts/Consolas.ttf",
        "2a65227ee5b4eaa896bec8a150d90d770ea4f2f59a2ef240eb6ae614951d3d77");
  }

  @Test
  public void referenceLayoutAndAnimationBaselineCannotDriftSilently() {
    String[][] files = {
        {"src/main/res/layout/aiw_activity_workbench.xml", "965d6708f9f235fd1040809bfe14d0c29dc07bc3f70503a45270f31a9d14d39d"},
        {"src/main/res/layout/aiw_item_browser_test.xml", "eb2dd50f9f25483c35cf3ef76b6394795aa4032190a31c708f319b0eb3686f19"},
        {"src/main/res/layout/aiw_item_context_file.xml", "20c0d9aa0014e01fd3f61eeb701da4e41633004100d8e8e5f1f6650ba1a2e370"},
        {"src/main/res/layout/aiw_item_plan.xml", "4d0d12ffc8cb9a4915d9dbb14c72146fe142146ffca9070a464b75d1b327b2a1"},
        {"src/main/res/layout/aiw_item_summary.xml", "4c6d313d6b4eddc6dcce6e15ca993f5c1c343bc169a649899666a5c4f6eae2e3"},
        {"src/main/res/layout/aiw_item_thought.xml", "08a2811529d2fd6ee8456eb2aa13c43a562f3b83118d3f209b23163f91858031"},
        {"src/main/res/layout/aiw_item_tool_running.xml", "8d1de29846fc7f5fa50fb88b5f82f21542518d61a466751da16c7f08a50b01a1"},
        {"src/main/res/layout/aiw_item_user_demand.xml", "3175359875cadb1cbd6c83052910ee88c25aaaa8285d9c65e6ce6d4d9461928b"},
        {"src/main/java/com/cscjapp/aiworkbench/android/widget/AnimatedDigitView.java", "5e3298d624ed3deff09a2b98538914a0140a013e3f02d134e0b7cd4c2582c759"},
        {"src/main/java/com/cscjapp/aiworkbench/android/widget/AnimatedNumberView.java", "47615525f140917e1de989768eafcf685835115b26376b12ff8cfa40487e8276"},
        {"src/main/java/com/cscjapp/aiworkbench/android/widget/DashedCircleLoadingView.java", "deaaafcf1789c5d5a39f2fa099cbb7257210fe604cb5457d1288d98f8fafb3d1"},
        {"src/main/java/com/cscjapp/aiworkbench/android/widget/WorkbenchWaitingAuraLayout.java", "28ba160d109f9ebb6b2085e3e90a6472691cbd7cd9cf3d214e85c72eef489ee7"},
        {"src/main/java/com/cscjapp/aiworkbench/android/widget/WorkbenchWaitingAuraView.java", "5870dc5d3c8c546706d2de886941cb7b5ab23628fe382f542e760176270c64b4"}
    };
    for (String[] file : files) assertSha256(file[0], file[1]);
  }

  @Test
  public void streamingUiUsesFrameScheduledPayloadRefresh() throws Exception {
    String activity =
        read("src/main/java/com/cscjapp/aiworkbench/android/AIWorkbenchActivity.java");
    String adapter =
        read("src/main/java/com/cscjapp/aiworkbench/android/WorkbenchItemAdapter.java");
    String viewModel =
        read("src/main/java/com/cscjapp/aiworkbench/android/WorkbenchViewModel.java");
    String layout = read("src/main/res/layout/aiw_item_summary.xml");
    assertTrue(activity.contains("Choreographer.FrameCallback"));
    assertTrue(activity.contains("STREAM_TEXT_FRAME_INTERVAL_NANOS = 33_000_000L"));
    assertTrue(activity.contains("viewModel.streamUiUpdates().observe"));
    assertTrue(activity.contains("new WorkbenchStreamPayload(mask, snapshot)"));
    assertTrue(activity.contains("STREAM_STATE_MIN_VISIBLE_MS = 220L"));
    assertTrue(activity.contains("STREAM_IDLE_THRESHOLD_MS = 10_000L"));
    assertTrue(activity.contains("模型仍在处理 · 已等待 "));
    assertTrue(activity.contains("if (isNearBottom(true)) scrollToBottom()"));
    assertTrue(activity.contains("else showNewUpdates()"));
    String targetedRefresh = methodBody(activity, "private void requestStreamUiRefresh");
    assertTrue(targetedRefresh.contains("drainStreamUiMailbox()"));
    assertTrue(!targetedRefresh.contains("setNewData"));
    String enqueue = methodBody(activity, "private void enqueueStreamUiUpdate");
    assertTrue(enqueue.contains("if (update.terminal)"));
    assertTrue(enqueue.contains("stopStreamIdleTicker()"));
    String flush = methodBody(activity, "private void flushStreamUiFrame");
    assertTrue(flush.contains("visibleFor < STREAM_STATE_MIN_VISIBLE_MS"));
    assertTrue(flush.contains("pendingStreamUiEvents.removeFirst()"));
    String destroy = methodBody(activity, "protected void onDestroy");
    assertTrue(destroy.contains("clearPendingStreamUiUpdates()"));
    assertTrue(adapter.contains("public void onBindViewHolder"));
    assertTrue(adapter.contains("STREAMING_COLLAPSED_PREVIEW_MAX_CHARS = 4096"));
    assertTrue(viewModel.contains("WorkbenchStreamUiMailbox streamUiMailbox"));
    assertTrue(viewModel.contains("WorkbenchStreamUiSnapshot.capture"));
    assertTrue(viewModel.contains("if (same) return;"));
    String codeLock = methodBody(adapter, "public void setCodeAreaLocked");
    assertTrue(codeLock.contains("PAYLOAD_CODE_AREA_LOCK"));
    assertTrue(!codeLock.contains("notifyDataSetChanged"));
    String avatar = methodBody(adapter, "void setUserAvatarUrl");
    assertTrue(!avatar.contains("notifyDataSetChanged"));
    assertTrue(layout.contains("app:aiw_animatedNumber_duration=\"220\""));
    assertTrue(layout.contains("app:aiw_animatedNumber_perDigitDelay=\"35\""));
  }

  @Test
  public void delayedDeltaUsesItsOriginalTimestampForIdleFeedback() {
    assertEquals(10_000L, AIWorkbenchActivity.streamIdleDelayMs(20_000L, 20_000L));
    assertEquals(5_000L, AIWorkbenchActivity.streamIdleDelayMs(25_000L, 20_000L));
    assertEquals(0L, AIWorkbenchActivity.streamIdleDelayMs(35_000L, 20_000L));
  }

  @Test
  public void managedPlanUiUsesNormalizedEvidenceWithoutSessionUpgrade() throws Exception {
    String viewModel =
        read("src/main/java/com/cscjapp/aiworkbench/android/WorkbenchViewModel.java");
    String render = methodBody(viewModel, "private void renderPlan");
    String advance = methodBody(viewModel, "private void advancePlanForTool");
    String submit = methodBody(viewModel, "synchronized void submit");
    String complete = methodBody(viewModel, "private void completePlan");

    assertTrue(render.contains("normalized_plan"));
    assertTrue(render.contains("applyPlanState"));
    assertTrue(render.contains("涉及文件："));
    assertTrue(render.contains("验证策略："));
    assertTrue(viewModel.contains("first(map, \"title\", \"action\", \"description\", \"name\")"));
    assertTrue(advance.contains("applyPlanState"));
    assertTrue(!advance.contains("advancePlan(true)"));
    assertTrue(submit.contains("clearActivePlan()"));
    assertTrue(complete.contains("detailExpanded = false"));
    assertTrue(viewModel.contains("new SessionSnapshot(3,"));
    assertTrue(!viewModel.contains("new SessionSnapshot(4,"));
  }

  @Test
  public void auraOptimizationPreservesVisualContractAndAllocatesOutsideDraw() throws Exception {
    String aura =
        read(
            "src/main/java/com/cscjapp/aiworkbench/android/widget/"
                + "WorkbenchWaitingAuraView.java");
    String viewModel =
        read("src/main/java/com/cscjapp/aiworkbench/android/WorkbenchViewModel.java");
    assertTrue(aura.contains("DEFAULT_BORDER_DURATION = 2200L"));
    assertTrue(aura.contains("DEFAULT_SWEEP_DURATION = 3200L"));
    assertTrue(aura.contains("DEFAULT_PRIMARY_COLOR = Color.rgb(116, 235, 213)"));
    assertTrue(aura.contains("DEFAULT_SECONDARY_COLOR = Color.rgb(143, 183, 255)"));
    assertTrue(aura.contains("DEFAULT_SWEEP_COLOR = Color.rgb(134, 239, 172)"));
    assertTrue(aura.contains("protected void onSizeChanged"));
    assertTrue(aura.contains("protected void onWindowVisibilityChanged"));
    assertTrue(aura.contains("public void onVisibilityAggregated"));
    String draw = methodBody(aura, "protected void onDraw");
    assertTrue(!draw.contains("new "));
    assertTrue(!draw.contains("new LinearGradient"));
    String geometry = methodBody(aura, "private void rebuildGeometry");
    assertTrue(geometry.contains("new LinearGradient"));
    assertTrue(viewModel.contains("for (WorkbenchUiItem candidate : state)"));
    assertTrue(viewModel.contains("candidate.waitingEffect = WorkbenchUiItem.WAITING_EFFECT_NONE"));
  }

  private static String read(String path) throws Exception {
    return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
  }

  private static String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    if (start < 0) throw new AssertionError("missing method " + signature);
    int opening = source.indexOf('{', start);
    int depth = 0;
    for (int index = opening; index < source.length(); index++) {
      char value = source.charAt(index);
      if (value == '{') depth++;
      if (value == '}' && --depth == 0) return source.substring(opening, index + 1);
    }
    throw new AssertionError("unterminated method " + signature);
  }

  private static void assertSha256(String path, String expected) {
    try {
      byte[] bytes = Files.readAllBytes(new File(path).toPath());
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder actual = new StringBuilder();
      for (byte value : digest) actual.append(String.format("%02x", value));
      assertTrue(path + " must remain byte-identical to the reference asset",
          expected.equals(actual.toString()));
    } catch (Exception error) {
      throw new AssertionError(path, error);
    }
  }
}
