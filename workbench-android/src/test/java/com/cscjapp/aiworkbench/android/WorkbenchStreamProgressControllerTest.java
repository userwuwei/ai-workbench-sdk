package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.core.ModelStreamDelta;
import com.cscjapp.aiworkbench.core.ToolCallStreamDelta;
import java.util.Collections;
import org.junit.Test;

public final class WorkbenchStreamProgressControllerTest {
  @Test
  public void mapsReasoningAndVisibleContentToReferenceLabels() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();

    WorkbenchStreamProgressController.Snapshot reasoning =
        controller.append(ModelStreamDelta.text("", "思考abc"), true);
    assertEquals(WorkbenchStreamProgressController.Kind.REASONING, reasoning.kind);
    assertEquals("已思考", reasoning.label);
    assertEquals(5L, reasoning.value);
    assertEquals("模型响应中", reasoning.content);
    assertFalse(reasoning.autoScroll);

    WorkbenchStreamProgressController.Snapshot input =
        controller.append(ModelStreamDelta.text("第一行\n", ""), true);
    assertEquals(WorkbenchStreamProgressController.Kind.INPUT, input.kind);
    assertEquals("已输入", input.label);
    assertEquals(4L, input.value);
    assertTrue(input.autoScroll);
    assertEquals("思考abc", input.reasoningText);
  }

  @Test
  public void classificationUsesCurrentDeltaWhileCountersRemainAccumulated() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();

    WorkbenchStreamProgressController.Snapshot input =
        controller.append(ModelStreamDelta.text("hello", ""), true);
    WorkbenchStreamProgressController.Snapshot reasoning =
        controller.append(ModelStreamDelta.text("", "think"), true);
    WorkbenchStreamProgressController.Snapshot moreInput =
        controller.append(ModelStreamDelta.text("!", ""), true);

    assertEquals(WorkbenchStreamProgressController.Kind.INPUT, input.kind);
    assertEquals(5L, input.value);
    assertEquals(WorkbenchStreamProgressController.Kind.REASONING, reasoning.kind);
    assertEquals("已思考", reasoning.label);
    assertEquals(5L, reasoning.value);
    assertEquals(WorkbenchStreamProgressController.Kind.INPUT, moreInput.kind);
    assertEquals(6L, moreInput.value);
  }

  @Test
  public void completedToolDeltaDoesNotPinLaterTextOrReasoningState() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();

    WorkbenchStreamProgressController.Snapshot received =
        controller.append(
            delta(
                new ToolCallStreamDelta(
                    0, "call_4", "syntax_check", "{\"path\":\"a.html\"}")),
            true);
    WorkbenchStreamProgressController.Snapshot input =
        controller.append(ModelStreamDelta.text("继续说明", ""), true);
    WorkbenchStreamProgressController.Snapshot reasoning =
        controller.append(ModelStreamDelta.text("", "继续思考"), true);

    assertEquals(WorkbenchStreamProgressController.Kind.RECEIVE, received.kind);
    assertEquals(WorkbenchStreamProgressController.Kind.INPUT, input.kind);
    assertEquals("已输入", input.label);
    assertEquals(WorkbenchStreamProgressController.Kind.REASONING, reasoning.kind);
    assertEquals("已思考", reasoning.label);

    WorkbenchStreamProgressController.Snapshot empty =
        controller.append(ModelStreamDelta.text("", ""), true);
    assertEquals(WorkbenchStreamProgressController.Kind.NONE, empty.kind);
  }

  @Test
  public void completedWriteDeltaDoesNotPinLaterReasoningState() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();

    WorkbenchStreamProgressController.Snapshot write =
        controller.append(
            delta(
                new ToolCallStreamDelta(
                    0, "call_5", "rewrite", "{\"path\":\"a.js\",\"content\":\"x\"}")),
            true);
    WorkbenchStreamProgressController.Snapshot reasoning =
        controller.append(ModelStreamDelta.text("", "核对结果"), true);

    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, write.kind);
    assertEquals(WorkbenchStreamProgressController.Kind.REASONING, reasoning.kind);
    assertEquals("已思考", reasoning.label);
  }

  @Test
  public void mixedDeltaKeepsToolCallPriority() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    ModelStreamDelta mixed =
        new ModelStreamDelta(
            "说明",
            "思考",
            Collections.singletonList(
                new ToolCallStreamDelta(
                    0, "call_6", "create_file", "{\"path\":\"new.html\"}")));

    WorkbenchStreamProgressController.Snapshot snapshot = controller.append(mixed, true);

    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, snapshot.kind);
    assertEquals("已写入", snapshot.label);
  }

  @Test
  public void assemblesFragmentedNativeWriteCallAndUsesArgumentsLength() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    controller.append(
        delta(new ToolCallStreamDelta(0, "call_1", "create_", "{\"path\":")), true);

    WorkbenchStreamProgressController.Snapshot snapshot =
        controller.append(
            delta(new ToolCallStreamDelta(0, "", "file", "\"a.html\",\"content\":\"hi\"}")),
            true);

    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, snapshot.kind);
    assertEquals("已写入", snapshot.label);
    assertEquals(
        "{\"path\":".length() + "\"a.html\",\"content\":\"hi\"}".length(),
        snapshot.value);
    assertEquals("接收工具参数", snapshot.runningName);
    assertEquals("接收中", snapshot.runningVerb);
    assertTrue(snapshot.toolCallVisible);
    assertFalse(snapshot.autoScroll);
  }

  @Test
  public void fragmentedToolNameCanTransitionFromReceivedToWriteState() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();

    WorkbenchStreamProgressController.Snapshot received =
        controller.append(
            delta(new ToolCallStreamDelta(0, "call_3", "search_", "{\"path\":")), true);
    WorkbenchStreamProgressController.Snapshot write =
        controller.append(
            delta(new ToolCallStreamDelta(0, "", "replace", "\"a.html\"}")), true);

    assertEquals(WorkbenchStreamProgressController.Kind.RECEIVE, received.kind);
    assertEquals("已接收", received.label);
    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, write.kind);
    assertEquals("已写入", write.label);
  }

  @Test
  public void nativeToolCallWinsOverVisibleContentAndCustomToolsUseReceived() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    ModelStreamDelta delta =
        new ModelStreamDelta(
            "可见文本",
            "推理",
            Collections.singletonList(
                new ToolCallStreamDelta(0, "call_2", "convert_video", "{\"format\":\"mp4\"}")));

    WorkbenchStreamProgressController.Snapshot snapshot = controller.append(delta, true);

    assertEquals(WorkbenchStreamProgressController.Kind.RECEIVE, snapshot.kind);
    assertEquals("已接收", snapshot.label);
    assertEquals("准备执行工具", snapshot.titleStage);
    assertEquals("可见文本", snapshot.content);
  }

  @Test
  public void legacyCreateAndSearchReplaceCountDecodedPayloadCharacters() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    WorkbenchStreamProgressController.Snapshot create =
        controller.append(
            ModelStreamDelta.text(
                "{\"next_action\":{\"tool\":\"create_file\",\"args\":{\"path\":\"src/a.html\",\"content\":\"a\\nb\\u4e2d",
                ""),
            false);
    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, create.kind);
    assertEquals("已写入", create.label);
    assertEquals(4L, create.value);
    assertEquals("正在向a.html写入代码", create.content);

    controller.reset();
    WorkbenchStreamProgressController.Snapshot replace =
        controller.append(
            ModelStreamDelta.text(
                "{\"next_action\":{\"tool\":\"search_replace\",\"args\":{\"path\":\"a.html\",\"old\":\"x\",\"new\":\"hello",
                ""),
            false);
    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, replace.kind);
    assertEquals(5L, replace.value);
  }

  @Test
  public void completedLegacyWriteDoesNotPinLaterNarrativeContent() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    WorkbenchStreamProgressController.Snapshot write =
        controller.append(
            ModelStreamDelta.text(
                "{\"next_action\":{\"tool\":\"rewrite\",\"args\":{"
                    + "\"path\":\"a.js\",\"content\":\"x\"}}}",
                ""),
            false);
    WorkbenchStreamProgressController.Snapshot narrative =
        controller.append(ModelStreamDelta.text("修改完成，准备验证。", ""), false);

    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, write.kind);
    assertEquals(WorkbenchStreamProgressController.Kind.INPUT, narrative.kind);
    assertEquals("已输入", narrative.label);
  }

  @Test
  public void nativeEndpointStillRecognizesLegacyProtocolFallback() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();

    WorkbenchStreamProgressController.Snapshot snapshot =
        controller.append(
            ModelStreamDelta.text(
                "{\"next_action\":\"tool\",\"tool\":\"rewrite\","
                    + "\"arguments\":{\"path\":\"src/main.js\",\"content\":\"let x=1;\"}}",
                ""),
            true);

    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, snapshot.kind);
    assertEquals("已写入", snapshot.label);
    assertEquals(8L, snapshot.value);
  }

  @Test
  public void nativeVisibleContentIsNotMistakenForLegacyWriteProtocol() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();

    WorkbenchStreamProgressController.Snapshot snapshot =
        controller.append(
            ModelStreamDelta.text(
                "I will call create_file and write the content field next.", ""),
            true);

    assertEquals(WorkbenchStreamProgressController.Kind.INPUT, snapshot.kind);
    assertEquals("已输入", snapshot.label);
  }

  @Test
  public void resetStartsNextRoundFromZero() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    controller.append(ModelStreamDelta.text("12345", ""), true);
    controller.reset();

    WorkbenchStreamProgressController.Snapshot snapshot =
        controller.append(ModelStreamDelta.text("x", ""), true);

    assertEquals(1L, snapshot.value);
    assertEquals("已输入", snapshot.label);
    assertFalse(controller.hasReasoning());
  }

  @Test
  public void nativeLargeArgumentsKeepOnlyLengthAndScrollOnlyOnFirstFragment() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    String chunk = repeat('x', 1000);
    WorkbenchStreamProgressController.Snapshot first =
        controller.append(
            delta(new ToolCallStreamDelta(0, "large", "create_file", chunk)), true);
    WorkbenchStreamProgressController.Snapshot latest = first;
    for (int index = 1; index < 100; index++) {
      latest =
          controller.append(
              delta(new ToolCallStreamDelta(0, "", "", chunk)), true);
    }

    assertTrue(first.autoScroll);
    assertFalse(latest.autoScroll);
    assertEquals(100_000L, latest.value);
  }

  @Test
  public void legacyParserProcessesFragmentedEscapesIncrementally() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    String json =
        "{\"next_action\":{\"args\":{\"content\":\"a\\nb\\u4e2d\\\"c\","
            + "\"path\":\"src/a.html\"},\"tool\":\"create_file\"}}";
    WorkbenchStreamProgressController.Snapshot latest = null;
    for (int index = 0; index < json.length(); index++) {
      latest = controller.append(ModelStreamDelta.text(json.substring(index, index + 1), ""), false);
    }

    assertEquals(WorkbenchStreamProgressController.Kind.WRITE, latest.kind);
    assertEquals(6L, latest.value);
    assertEquals("正在向a.html写入代码", latest.content);
  }

  @Test
  public void visibleTextSnapshotsAreRateLimitedWhileCountRemainsExact() {
    WorkbenchStreamProgressController controller = new WorkbenchStreamProgressController();
    WorkbenchStreamProgressController.Snapshot latest = null;
    for (int index = 0; index < 20_000; index++) {
      latest = controller.append(ModelStreamDelta.text("x", ""), true);
    }

    assertEquals(20_000L, latest.value);
    assertTrue(controller.visibleSnapshotBuildCountForTest() < 1_000);
  }

  private static String repeat(char value, int count) {
    StringBuilder output = new StringBuilder(count);
    for (int index = 0; index < count; index++) output.append(value);
    return output.toString();
  }

  private static ModelStreamDelta delta(ToolCallStreamDelta call) {
    return new ModelStreamDelta("", "", Collections.singletonList(call));
  }
}
