package com.cscjapp.aiworkbench.sample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.core.AgentMessage;
import com.cscjapp.aiworkbench.core.ModelRequest;
import com.cscjapp.aiworkbench.core.ModelResponse;
import com.cscjapp.aiworkbench.core.ModelStreamDelta;
import com.cscjapp.aiworkbench.core.ModelStreamObserver;
import com.cscjapp.aiworkbench.api.ToolSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ScriptedModelGatewayTest {
  @Test
  public void nativeToolStreamsReasoningAndAssemblesFinalCall() throws Exception {
    ScriptedModelGateway gateway = new ScriptedModelGateway(ignored -> {});
    CountDownLatch done = new CountDownLatch(1);
    StringBuilder reasoning = new StringBuilder();
    StringBuilder arguments = new StringBuilder();
    AtomicReference<ModelResponse> response = new AtomicReference<>();

    gateway.stream(
        request("请运行 echo", true),
        observer(
            delta -> {
              reasoning.append(delta.reasoning());
              delta.toolCalls().forEach(call -> arguments.append(call.arguments()));
            },
            value -> {
              response.set(value);
              done.countDown();
            },
            error -> done.countDown()));

    assertTrue(done.await(3, TimeUnit.SECONDS));
    assertTrue(reasoning.length() > 0);
    assertTrue(arguments.toString().contains("text"));
    assertEquals("echo", response.get().toolCalls().get(0).name());
  }

  @Test
  public void legacyModeStreamsParseableJson() throws Exception {
    ScriptedModelGateway gateway = new ScriptedModelGateway(ignored -> {});
    CountDownLatch done = new CountDownLatch(1);
    StringBuilder content = new StringBuilder();
    AtomicReference<ModelResponse> response = new AtomicReference<>();

    gateway.stream(
        request("请读取 README.md", false),
        observer(
            delta -> content.append(delta.content()),
            value -> {
              response.set(value);
              done.countDown();
            },
            error -> done.countDown()));

    assertTrue(done.await(3, TimeUnit.SECONDS));
    assertEquals(content.toString(), response.get().content());
    assertTrue(content.toString().contains("\"tool\":\"read_file\""));
    assertTrue(response.get().toolCalls().isEmpty());
  }

  @Test
  public void cancellationStopsTerminalCallback() throws Exception {
    ScriptedModelGateway gateway = new ScriptedModelGateway(ignored -> {});
    CountDownLatch terminal = new CountDownLatch(1);
    com.cscjapp.aiworkbench.api.Cancellable active =
        gateway.stream(
            request("请模拟超时", true),
            observer(delta -> {}, value -> terminal.countDown(), error -> terminal.countDown()));
    active.cancel();
    assertFalse(terminal.await(250, TimeUnit.MILLISECONDS));
  }

  @Test
  public void longTextScenarioProducesExactlyTwentyThousandCharacters() throws Exception {
    ScriptedModelGateway gateway = new ScriptedModelGateway(ignored -> {});
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ModelResponse> response = new AtomicReference<>();
    gateway.stream(
        request("请生成 20000 字长文本性能场景", true),
        observer(
            delta -> {},
            value -> {
              response.set(value);
              done.countDown();
            },
            error -> done.countDown()));
    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertEquals(20_000, response.get().content().length());
  }

  @Test
  public void largeToolScenarioProducesExactlyOneHundredThousandCharacters() throws Exception {
    ScriptedModelGateway gateway = new ScriptedModelGateway(ignored -> {});
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ModelResponse> response = new AtomicReference<>();
    gateway.stream(
        request("请运行 100000 字长参数工具场景", true),
        observer(
            delta -> {},
            value -> {
              response.set(value);
              done.countDown();
            },
            error -> done.countDown()));
    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertEquals(
        100_000,
        response.get().toolCalls().get(0).arguments().getString("text", "").length());
  }

  @Test
  public void codeAgentNativeAndLegacyFollowTheCompleteDeterministicSequence()
      throws Exception {
    List<String> previous =
        Arrays.asList(
            "",
            "plan_task",
            "read_file",
            "rewrite",
            "verify_workspace",
            "quality_review");
    List<String> expected =
        Arrays.asList(
            "plan_task",
            "read_file",
            "rewrite",
            "verify_workspace",
            "quality_review",
            "finalize_task");
    for (boolean nativeTools : new boolean[] {true, false}) {
      for (int index = 0; index < expected.size(); index++) {
        ModelResponse response =
            complete(
                new ScriptedModelGateway(ignored -> {}),
                codeAgentRequest(nativeTools, previous.get(index)));
        if (nativeTools) {
          assertEquals(expected.get(index), response.toolCalls().get(0).name());
        } else {
          assertTrue(
              response.content().contains(
                  "\"tool\":\"" + expected.get(index) + "\""));
        }
      }
    }
  }

  private static ModelRequest request(String demand, boolean nativeTools) {
    return new ModelRequest(
        new ModelEndpoint(
            "offline://playground", "", "scripted", 0.2, nativeTools, true),
        Arrays.asList(AgentMessage.system("test"), AgentMessage.user(demand)),
        Collections.emptyList(),
        true);
  }

  private static ModelRequest codeAgentRequest(boolean nativeTools, String previousTool) {
    java.util.ArrayList<AgentMessage> messages = new java.util.ArrayList<>();
    messages.add(AgentMessage.system("code agent"));
    messages.add(AgentMessage.user("执行 Code Agent 闭环"));
    if (previousTool != null && !previousTool.isEmpty()) {
      messages.add(AgentMessage.tool("previous", previousTool, "{\"status\":\"success\"}"));
    }
    return new ModelRequest(
        new ModelEndpoint(
            "offline://playground", "", "scripted", 0.2, nativeTools, true),
        messages,
        Collections.singletonList(
            new ToolSpec(
                "finalize_task",
                "terminal",
                Collections.singletonMap("type", "object"))),
        true);
  }

  private static ModelResponse complete(
      ScriptedModelGateway gateway, ModelRequest request) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ModelResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    gateway.stream(
        request,
        observer(
            delta -> {},
            value -> {
              response.set(value);
              done.countDown();
            },
            error -> {
              failure.set(error);
              done.countDown();
            }));
    assertTrue(done.await(3, TimeUnit.SECONDS));
    if (failure.get() != null) throw new AssertionError(failure.get());
    return response.get();
  }

  private static ModelStreamObserver observer(
      java.util.function.Consumer<ModelStreamDelta> delta,
      java.util.function.Consumer<ModelResponse> complete,
      java.util.function.Consumer<Throwable> error) {
    return new ModelStreamObserver() {
      @Override
      public void onDelta(String content, String reasoning) {}

      @Override
      public void onStreamDelta(ModelStreamDelta value) {
        delta.accept(value);
      }

      @Override
      public void onComplete(ModelResponse value) {
        complete.accept(value);
      }

      @Override
      public void onError(Throwable value) {
        error.accept(value);
      }
    };
  }
}
