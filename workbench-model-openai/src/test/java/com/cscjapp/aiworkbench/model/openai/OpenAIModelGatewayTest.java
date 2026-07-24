package com.cscjapp.aiworkbench.model.openai;

import static org.junit.Assert.*;
import com.cscjapp.aiworkbench.api.*;
import com.cscjapp.aiworkbench.core.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.*;

public class OpenAIModelGatewayTest {
  private MockWebServer server;

  @Before
  public void setup() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  public void assemblesFragmentedNativeToolCall() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"create_\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"file\",\"arguments\":\"\\\"a.html\\\"}\"}}]}}],\"finish_reason\":\"tool_calls\"}\n\n"
                    + "data: [DONE]\n\n"));
    CountDownLatch latch = new CountDownLatch(1);
    List<ModelResponse> responses = new ArrayList<>();
    List<ModelStreamDelta> deltas = new ArrayList<>();
    new OpenAIModelGateway()
        .stream(
            request(),
            new ModelStreamObserver() {
              public void onDelta(String c, String r) {}

              public void onStreamDelta(ModelStreamDelta delta) {
                deltas.add(delta);
              }

              public void onComplete(ModelResponse r) {
                responses.add(r);
                latch.countDown();
              }

              public void onError(Throwable e) {
                e.printStackTrace();
                latch.countDown();
              }
            });
    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertEquals(1, responses.size());
    assertEquals(2, deltas.size());
    assertEquals("create_", deltas.get(0).toolCalls().get(0).name());
    assertEquals("file", deltas.get(1).toolCalls().get(0).name());
    assertEquals("{\"path\":", deltas.get(0).toolCalls().get(0).arguments());
    assertEquals("create_file", responses.get(0).toolCalls().get(0).name());
    assertEquals("a.html", responses.get(0).toolCalls().get(0).arguments().getString("path", ""));
  }

  @Test
  public void repairsOnlyOneTrailingToolArgumentBrace() {
    OpenAIModelGateway gateway = new OpenAIModelGateway();

    Map<String, Object> repaired =
        gateway.parseToolArguments("{\"goal\":\"repair\"}}");
    assertEquals("repair", repaired.get("goal"));
    assertFalse(repaired.containsKey("__raw_arguments"));

    Map<String, Object> malformed =
        gateway.parseToolArguments("{\"goal\":\"repair\"}}}");
    assertTrue(malformed.containsKey("__raw_arguments"));
    assertEquals("{\"goal\":\"repair\"}}}", malformed.get("__raw_arguments"));
  }

  @Test
  public void legacyOnDeltaObserverReceivesContentExactlyOnce() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"two\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n"));
    CountDownLatch latch = new CountDownLatch(1);
    List<String> chunks = new ArrayList<>();

    new OpenAIModelGateway()
        .stream(
            request(),
            new ModelStreamObserver() {
              public void onDelta(String content, String reasoning) {
                chunks.add(content);
              }

              public void onComplete(ModelResponse response) {
                latch.countDown();
              }

              public void onError(Throwable error) {
                latch.countDown();
              }
            });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertEquals(Arrays.asList("one", "two"), chunks);
  }

  @Test
  public void appliesDynamicHostHeadersWithoutLeakingTransportTypes() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n"));
    CountDownLatch latch = new CountDownLatch(1);
    ModelEndpoint endpoint =
        new ModelEndpoint(
            server.url("/v1").toString(),
            "secret",
            "test",
            0.2,
            false,
            false,
            ignored -> Collections.singletonMap("X-Host-Sign", "signed"));
    ModelRequest request =
        new ModelRequest(
            endpoint,
            Arrays.asList(AgentMessage.system("s"), AgentMessage.user("u")),
            Collections.emptyList(),
            false);
    new OpenAIModelGateway()
        .stream(
            request,
            new ModelStreamObserver() {
              public void onDelta(String c, String r) {}

              public void onComplete(ModelResponse r) {
                latch.countDown();
              }

              public void onError(Throwable e) {
                latch.countDown();
              }
            });
    assertTrue(latch.await(3, TimeUnit.SECONDS));
    RecordedRequest recorded = server.takeRequest(3, TimeUnit.SECONDS);
    assertNotNull(recorded);
    assertEquals("signed", recorded.getHeader("X-Host-Sign"));
    assertEquals("Bearer secret", recorded.getHeader("Authorization"));
  }

  @Test
  public void supportsOpenAiCompatibleNonStreamingJsonFallback() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"choices\":[{\"message\":{\"content\":\"done\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"create_file\",\"arguments\":{\"path\":\"src/index.html\"}}}]},\"finish_reason\":\"tool_calls\"}]}"));
    CountDownLatch latch = new CountDownLatch(1);
    List<ModelResponse> responses = new ArrayList<>();
    List<ModelStreamDelta> deltas = new ArrayList<>();
    new OpenAIModelGateway()
        .stream(
            request(),
            new ModelStreamObserver() {
              public void onDelta(String c, String r) {}

              public void onStreamDelta(ModelStreamDelta delta) {
                deltas.add(delta);
              }

              public void onComplete(ModelResponse r) {
                responses.add(r);
                latch.countDown();
              }

              public void onError(Throwable e) {
                latch.countDown();
              }
            });
    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertEquals(1, responses.size());
    assertEquals(1, deltas.size());
    assertEquals("done", deltas.get(0).content());
    assertEquals("create_file", deltas.get(0).toolCalls().get(0).name());
    assertEquals("done", responses.get(0).content());
    assertEquals(
        "src/index.html", responses.get(0).toolCalls().get(0).arguments().getString("path", ""));
  }

  @Test
  public void logsRequestAndFinalResponseWithoutAuthorization() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n"));
    CountDownLatch latch = new CountDownLatch(1);
    List<String> events = new ArrayList<>();
    WorkbenchLogger logger = (event, message) -> events.add(event + "\n" + message);

    new OpenAIModelGateway(logger)
        .stream(
            request(),
            new ModelStreamObserver() {
              public void onDelta(String content, String reasoning) {}

              public void onComplete(ModelResponse response) {
                latch.countDown();
              }

              public void onError(Throwable error) {
                latch.countDown();
              }
            });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
    String requestEvent = firstEvent(events, "model_request");
    assertTrue(requestEvent.contains("[模型请求][request=1][stage=initial][model=test]"));
    assertTrue(requestEvent.contains("serialized_chars="));
    assertTrue(requestEvent.contains("[message=1][role=user]\nu"));
    assertFalse(requestEvent.contains("body="));
    assertFalse(requestEvent.contains("Bearer secret"));
    assertTrue(firstContaining(events, "[event=headers]").contains("content_type=text/event-stream"));
    String firstDelta = firstContaining(events, "[event=first_delta]");
    assertTrue(firstDelta.contains("type=mixed"));
    assertTrue(firstDelta.contains("content_chars=4"));
    assertTrue(firstDelta.contains("reasoning_chars=5"));
    assertTrue(firstContaining(events, "[event=finish_reason]").contains("value=stop"));
    String doneEvent = firstContaining(events, "[event=done]");
    assertTrue(doneEvent.contains("finish_to_done_ms="));
    assertTrue(doneEvent.contains("last_delta_to_done_ms="));
    String responseEvent = firstContaining(events, "[模型响应][request=1]");
    assertTrue(responseEvent.contains("[finish_reason=stop]"));
    assertTrue(firstContaining(events, "[content]done").contains("[本轮模型返回]"));
    assertTrue(firstContaining(events, "[reasoning]think").contains("[本轮模型返回]"));
    for (String event : events) {
      if (!event.startsWith("model_stream")) continue;
      assertFalse(event.contains("[本轮模型返回]"));
      assertFalse(event.contains("content=done"));
      assertFalse(event.contains("think"));
    }
  }

  @Test
  public void logsEofWhenCompatibleStreamOmitsDoneSentinel() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"));
    List<String> events = new ArrayList<>();
    OpenAIModelGateway gateway =
        new OpenAIModelGateway((event, message) -> events.add(event + "\n" + message));

    await(gateway, request());

    String eofEvent = firstContaining(events, "[event=eof]");
    assertTrue(eofEvent.contains("finish_to_eof_ms="));
    assertTrue(eofEvent.contains("last_delta_to_eof_ms="));
    assertFalse(join(events).contains("[event=done]"));
  }

  @Test
  public void ignoresEmptyCommentAndNonJsonHeartbeatsWithoutReportingProgress() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                ": heartbeat\n\n"
                    + "data: \n\n"
                    + "data: ping\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n"));
    List<String> events = new ArrayList<>();
    List<ModelStreamDelta> deltas = new ArrayList<>();
    List<Throwable> errors = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    OpenAIModelGateway gateway =
        new OpenAIModelGateway((event, message) -> events.add(event + "\n" + message));

    gateway.stream(
        request(),
        new ModelStreamObserver() {
          public void onDelta(String content, String reasoning) {}

          public void onStreamDelta(ModelStreamDelta delta) {
            deltas.add(delta);
          }

          public void onComplete(ModelResponse response) {
            latch.countDown();
          }

          public void onError(Throwable error) {
            errors.add(error);
            latch.countDown();
          }
        });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertTrue(errors.toString(), errors.isEmpty());
    assertEquals(1, deltas.size());
    assertEquals("ok", deltas.get(0).content());
    assertTrue(firstContaining(events, "[event=first_delta]").contains("content_chars=2"));
    assertTrue(firstContaining(events, "[event=done]").contains("ignored_heartbeat_count=1"));
  }

  @Test
  public void continuationLogOnlyIncludesNewMessages() throws Exception {
    server.enqueue(jsonResponse("first"));
    server.enqueue(jsonResponse("second"));
    List<String> events = new ArrayList<>();
    WorkbenchLogger logger = (event, message) -> events.add(event + "\n" + message);
    OpenAIModelGateway gateway = new OpenAIModelGateway(logger);

    await(gateway, request());
    ModelRequest continuation =
        new ModelRequest(
            request().endpoint(),
            Arrays.asList(
                AgentMessage.system("s"),
                AgentMessage.user("u"),
                AgentMessage.assistant("first", Collections.emptyList()),
                AgentMessage.tool("call-1", "read_file", "{\"status\":\"success\"}")),
            request().tools(),
            false);
    await(gateway, continuation);

    String secondRequest = firstContaining(events, "[模型请求][request=2]");
    assertTrue(secondRequest.contains("[模型请求][request=2][stage=continue][model=test]"));
    assertTrue(secondRequest.contains("new_messages=2"));
    assertFalse(secondRequest.contains("[message=0]"));
    assertFalse(secondRequest.contains("[message=1]"));
    assertTrue(secondRequest.contains("[message=2][role=assistant]\nfirst"));
    assertTrue(secondRequest.contains("[message=3][role=tool]"));
  }

  private static MockResponse jsonResponse(String content) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"choices\":[{\"message\":{\"content\":\""
                + content
                + "\"},\"finish_reason\":\"stop\"}]}");
  }

  private static String firstEvent(List<String> events, String eventName) {
    for (String event : events) {
      if (event.startsWith(eventName + "\n")) return event;
    }
    fail("missing event " + eventName + " in " + events);
    return "";
  }

  private static String firstContaining(List<String> events, String needle) {
    for (String event : events) {
      if (event.contains(needle)) return event;
    }
    fail("missing log " + needle + " in " + events);
    return "";
  }

  private static String join(List<String> events) {
    StringBuilder result = new StringBuilder();
    for (String event : events) result.append(event).append('\n');
    return result.toString();
  }

  private static void await(OpenAIModelGateway gateway, ModelRequest request) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<Throwable> errors = new ArrayList<>();
    gateway.stream(
        request,
        new ModelStreamObserver() {
          public void onDelta(String content, String reasoning) {}

          public void onComplete(ModelResponse response) {
            latch.countDown();
          }

          public void onError(Throwable error) {
            errors.add(error);
            latch.countDown();
          }
        });
    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertTrue(errors.toString(), errors.isEmpty());
  }

  private ModelRequest request() {
    ModelEndpoint endpoint =
        new ModelEndpoint(server.url("/v1").toString(), "secret", "test", 0.2, true, false);
    ToolSpec spec =
        new ToolSpec("create_file", "create", Collections.singletonMap("type", "object"));
    return new ModelRequest(
        endpoint,
        Arrays.asList(AgentMessage.system("s"), AgentMessage.user("u")),
        Collections.singletonList(spec),
        false);
  }
}
