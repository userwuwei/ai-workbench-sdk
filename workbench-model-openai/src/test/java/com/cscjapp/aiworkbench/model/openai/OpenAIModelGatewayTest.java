package com.cscjapp.aiworkbench.model.openai;

import static org.junit.Assert.*;
import com.cscjapp.aiworkbench.api.*;
import com.cscjapp.aiworkbench.core.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
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
  public void requestsAndParsesOptionalCachedTokenUsage() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":20,\"total_tokens\":1020,\"prompt_tokens_details\":{\"cached_tokens\":800}}}\n\n"
                    + "data: [DONE]\n\n"));
    CountDownLatch latch = new CountDownLatch(1);
    List<ModelResponse> responses = new ArrayList<>();

    new OpenAIModelGateway().stream(request(), new ModelStreamObserver() {
      public void onDelta(String content, String reasoning) {}
      public void onComplete(ModelResponse response) {
        responses.add(response);
        latch.countDown();
      }
      public void onError(Throwable error) { latch.countDown(); }
    });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertEquals(1000L, responses.get(0).usage().inputTokens());
    assertEquals(800L, responses.get(0).usage().cachedTokens());
    assertEquals(200L, responses.get(0).usage().uncachedTokens());
    assertEquals(80, responses.get(0).usage().cachedPercent());
    RecordedRequest recorded = server.takeRequest(3, TimeUnit.SECONDS);
    assertNotNull(recorded);
    assertTrue(recorded.getBody().readUtf8().contains("\"include_usage\":true"));
  }

  @Test
  public void retriesWithoutUsageOptionWhenCompatibleProviderRejectsIt() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(400)
        .setBody("{\"error\":\"unknown field stream_options\"}"));
    server.enqueue(new MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n"));
    CountDownLatch latch = new CountDownLatch(1);
    List<ModelResponse> responses = new ArrayList<>();

    new OpenAIModelGateway().stream(request(), new ModelStreamObserver() {
      public void onDelta(String content, String reasoning) {}
      public void onComplete(ModelResponse response) {
        responses.add(response);
        latch.countDown();
      }
      public void onError(Throwable error) { latch.countDown(); }
    });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertEquals("ok", responses.get(0).content());
    RecordedRequest first = server.takeRequest(3, TimeUnit.SECONDS);
    RecordedRequest second = server.takeRequest(3, TimeUnit.SECONDS);
    assertTrue(first.getBody().readUtf8().contains("stream_options"));
    assertFalse(second.getBody().readUtf8().contains("stream_options"));
  }

  @Test
  public void rejectsMalformedArgumentsWithoutRepairingOrDispatchableFallback() throws Exception {
    enqueueToolArguments("{\"path\":\"index.html\",\"content\":\"\\U0001f40d\"}");

    ModelResponse response = streamResponse(request());

    assertTrue(response.toolCalls().isEmpty());
    assertEquals(1, response.invalidToolCalls().size());
    InvalidToolCall invalid = response.invalidToolCalls().get(0);
    assertEquals("create_file", invalid.name());
    assertEquals("\\U", invalid.invalidEscape());
    assertTrue(invalid.errorOffset() >= 0);
    assertFalse(invalid.errorMessage().contains("path required"));
  }

  @Test
  public void rejectsBraceAndCodePointEscapesInsteadOfRepairingThem() throws Exception {
    enqueueToolArguments("{\"goal\":\"repair\"}}");
    ModelResponse trailingBrace = streamResponse(request());
    assertTrue(trailingBrace.toolCalls().isEmpty());
    assertEquals(1, trailingBrace.invalidToolCalls().size());

    enqueueToolArguments("{\"content\":\"\\u{1F40D}\"}");
    ModelResponse codePointEscape = streamResponse(request());
    assertTrue(codePointEscape.toolCalls().isEmpty());
    assertEquals("\\u{", codePointEscape.invalidToolCalls().get(0).invalidEscape());
  }

  @Test
  public void rejectsLenientJsonSyntax() throws Exception {
    enqueueToolArguments("{'path':'index.html'}");
    ModelResponse singleQuotes = streamResponse(request());
    assertTrue(singleQuotes.toolCalls().isEmpty());
    assertEquals(1, singleQuotes.invalidToolCalls().size());

    enqueueToolArguments("{path:\"index.html\"}");
    ModelResponse unquotedName = streamResponse(request());
    assertTrue(unquotedName.toolCalls().isEmpty());
    assertEquals(1, unquotedName.invalidToolCalls().size());
  }

  @Test
  public void roundTripsUnicodeQuotesNewlinesTemplatesAndSourceBackslashes() throws Exception {
    String content =
        "🐍 \"quoted\"\nconst value = `x`;\nconst escaped = \"\\\\u{1F40D}\";";
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("path", "src/index.html");
    arguments.put("content", content);
    arguments.put("file_role", "entry_source");
    enqueueToolArguments(new Gson().toJson(arguments));

    ModelResponse response = streamResponse(request());

    assertTrue(response.invalidToolCalls().isEmpty());
    assertArrayEquals(
        content.getBytes(StandardCharsets.UTF_8),
        response
            .toolCalls()
            .get(0)
            .arguments()
            .getString("content", "")
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void sendsStrictOnlyWhenEndpointAndToolBothOptIn() throws Exception {
    server.enqueue(stopResponse());
    await(new OpenAIModelGateway(), request(ToolArgumentMode.STRICT, true));
    JsonObject strictBody =
        JsonParser.parseString(server.takeRequest(3, TimeUnit.SECONDS).getBody().readUtf8())
            .getAsJsonObject();
    assertTrue(
        strictBody
            .getAsJsonArray("tools")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("function")
            .get("strict")
            .getAsBoolean());

    server.enqueue(stopResponse());
    await(new OpenAIModelGateway(), request(ToolArgumentMode.BEST_EFFORT, true));
    JsonObject bestEffortBody =
        JsonParser.parseString(server.takeRequest(3, TimeUnit.SECONDS).getBody().readUtf8())
            .getAsJsonObject();
    assertFalse(
        bestEffortBody
            .getAsJsonArray("tools")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("function")
            .has("strict"));

    server.enqueue(stopResponse());
    await(new OpenAIModelGateway(), request(ToolArgumentMode.STRICT, false));
    JsonObject nonStrictToolBody =
        JsonParser.parseString(server.takeRequest(3, TimeUnit.SECONDS).getBody().readUtf8())
            .getAsJsonObject();
    assertFalse(
        nonStrictToolBody
            .getAsJsonArray("tools")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("function")
            .has("strict"));
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

  private void enqueueToolArguments(String arguments) {
    JsonObject function = new JsonObject();
    function.addProperty("name", "create_file");
    function.addProperty("arguments", arguments);
    JsonObject call = new JsonObject();
    call.addProperty("id", "call-invalid");
    call.addProperty("type", "function");
    call.add("function", function);
    com.google.gson.JsonArray calls = new com.google.gson.JsonArray();
    calls.add(call);
    JsonObject message = new JsonObject();
    message.addProperty("content", "");
    message.add("tool_calls", calls);
    JsonObject choice = new JsonObject();
    choice.add("message", message);
    choice.addProperty("finish_reason", "tool_calls");
    com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
    choices.add(choice);
    JsonObject root = new JsonObject();
    root.add("choices", choices);
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(new Gson().toJson(root)));
  }

  private ModelResponse streamResponse(ModelRequest request) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<ModelResponse> responses = new ArrayList<>();
    List<Throwable> errors = new ArrayList<>();
    new OpenAIModelGateway()
        .stream(
            request,
            new ModelStreamObserver() {
              public void onDelta(String content, String reasoning) {}

              public void onComplete(ModelResponse response) {
                responses.add(response);
                latch.countDown();
              }

              public void onError(Throwable error) {
                errors.add(error);
                latch.countDown();
              }
            });
    assertTrue(latch.await(3, TimeUnit.SECONDS));
    assertTrue(errors.toString(), errors.isEmpty());
    assertEquals(1, responses.size());
    return responses.get(0);
  }

  private static MockResponse stopResponse() {
    return new MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n");
  }

  private ModelRequest request() {
    return request(ToolArgumentMode.BEST_EFFORT, false);
  }

  private ModelRequest request(ToolArgumentMode mode, boolean strictSchema) {
    ModelEndpoint endpoint =
        new ModelEndpoint(
            server.url("/v1").toString(), "secret", "test", 0.2, true, false, mode);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Collections.emptyMap());
    schema.put("required", Collections.emptyList());
    schema.put("additionalProperties", false);
    ToolSpec spec =
        new ToolSpec("create_file", "create", schema, strictSchema);
    return new ModelRequest(
        endpoint,
        Arrays.asList(AgentMessage.system("s"), AgentMessage.user("u")),
        Collections.singletonList(spec),
        false);
  }
}
