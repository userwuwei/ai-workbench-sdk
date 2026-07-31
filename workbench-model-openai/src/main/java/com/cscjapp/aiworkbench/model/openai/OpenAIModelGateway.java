package com.cscjapp.aiworkbench.model.openai;

import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolArgumentMode;
import com.cscjapp.aiworkbench.api.ToolSpec;
import com.cscjapp.aiworkbench.core.AgentMessage;
import com.cscjapp.aiworkbench.core.AgentToolCall;
import com.cscjapp.aiworkbench.core.InvalidToolCall;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.ModelRequest;
import com.cscjapp.aiworkbench.core.ModelResponse;
import com.cscjapp.aiworkbench.core.ModelUsage;
import com.cscjapp.aiworkbench.core.ModelStreamDelta;
import com.cscjapp.aiworkbench.core.ModelStreamObserver;
import com.cscjapp.aiworkbench.core.ToolCallStreamDelta;
import com.cscjapp.aiworkbench.core.WorkbenchLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** OpenAI-compatible streaming transport. No OkHttp type leaks into the public SDK API. */
public final class OpenAIModelGateway implements ModelGateway {
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private static final long STREAM_GAP_LOG_THRESHOLD_MS = 1000L;
  private static final Pattern JSON_COLUMN = Pattern.compile("\\bcolumn\\s+(\\d+)\\b");
  private final OkHttpClient client;
  private final Gson gson = new Gson();
  private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
  private final WorkbenchLogger logger;
  private final AtomicInteger requestSequence = new AtomicInteger();
  private int previousMessageCount;

  public OpenAIModelGateway() {
    this(WorkbenchLogger.none());
  }

  public OpenAIModelGateway(WorkbenchLogger logger) {
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)
            .build();
    this.logger = logger == null ? WorkbenchLogger.none() : logger;
  }

  @Override
  public Cancellable stream(ModelRequest modelRequest, ModelStreamObserver observer) {
    long startedAt = System.currentTimeMillis();
    int requestIndex = requestSequence.incrementAndGet();
    ModelEndpoint endpoint = modelRequest.endpoint();
    if (endpoint == null || blank(endpoint.baseUrl()) || blank(endpoint.modelId())) {
      IllegalArgumentException error = new IllegalArgumentException("模型地址和模型名称不能为空");
      logError(error, startedAt, requestIndex, endpoint == null ? "" : endpoint.modelId());
      observer.onError(error);
      return Cancellable.NONE;
    }
    String url = chatUrl(endpoint.baseUrl());
    String requestJson = gson.toJson(buildRequest(modelRequest, true));
    logRequest(modelRequest, url, requestIndex, requestJson.length());
    AtomicBoolean terminal = new AtomicBoolean();
    AtomicReference<Call> activeCall = new AtomicReference<>();
    enqueue(
        modelRequest,
        observer,
        endpoint,
        url,
        startedAt,
        requestIndex,
        true,
        terminal,
        activeCall);
    return () -> {
      Call call = activeCall.get();
      if (call != null) call.cancel();
    };
  }

  private void enqueue(
      ModelRequest modelRequest,
      ModelStreamObserver observer,
      ModelEndpoint endpoint,
      String url,
      long startedAt,
      int requestIndex,
      boolean includeUsage,
      AtomicBoolean terminal,
      AtomicReference<Call> activeCall) {
    String requestJson = gson.toJson(buildRequest(modelRequest, includeUsage));
    Request.Builder request =
        new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, requestJson))
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json");
    if (!blank(endpoint.apiKey())) {
      request.header("Authorization", "Bearer " + endpoint.apiKey());
    }
    Map<String, String> extraHeaders = endpoint.headerProvider().headers(endpoint);
    if (extraHeaders != null) {
      for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
        if (!blank(header.getKey()) && header.getValue() != null) {
          request.header(header.getKey(), header.getValue());
        }
      }
    }
    Call call = client.newCall(request.build());
    activeCall.set(call);
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call ignored, IOException error) {
            if (terminal.compareAndSet(false, true)) {
              logError(error, startedAt, requestIndex, endpoint.modelId());
              observer.onError(error);
            }
          }

          @Override
          public void onResponse(Call ignored, Response response) {
            try (Response closeable = response) {
              if (!response.isSuccessful() || response.body() == null) {
                String detail = response.body() == null ? "" : response.body().string();
                if (includeUsage
                    && response.code() == 400
                    && unsupportedUsageOption(detail)
                    && !terminal.get()) {
                  logStream(requestIndex, "usage_fallback", "retry_without_stream_options=true");
                  enqueue(
                      modelRequest,
                      observer,
                      endpoint,
                      url,
                      startedAt,
                      requestIndex,
                      false,
                      terminal,
                      activeCall);
                  return;
                }
                throw new IOException("模型请求失败 HTTP " + response.code() + safeDetail(detail));
              }
              String contentType = response.header("Content-Type", "");
              logStream(
                  requestIndex,
                  "headers",
                  "elapsed_ms="
                      + (System.currentTimeMillis() - startedAt)
                      + ",http="
                      + response.code()
                      + ",content_type="
                      + compactContentType(contentType));
              if (contentType.toLowerCase().contains("text/event-stream")) {
                parseSse(
                    response,
                    observer,
                    terminal,
                    startedAt,
                    requestIndex,
                    endpoint.modelId());
              } else {
                parseJsonResponse(
                    response.body().string(),
                    observer,
                    terminal,
                    startedAt,
                    requestIndex,
                    endpoint.modelId());
              }
            } catch (Throwable error) {
              if (terminal.compareAndSet(false, true)) {
                logError(error, startedAt, requestIndex, endpoint.modelId());
                observer.onError(error);
              }
            }
          }
        });
  }

  private JsonObject buildRequest(ModelRequest request) {
    return buildRequest(request, true);
  }

  private JsonObject buildRequest(ModelRequest request, boolean includeUsage) {
    JsonObject root = new JsonObject();
    root.addProperty("model", request.endpoint().modelId());
    root.addProperty("stream", true);
    root.addProperty("temperature", request.endpoint().temperature());
    if (includeUsage) {
      JsonObject streamOptions = new JsonObject();
      streamOptions.addProperty("include_usage", true);
      root.add("stream_options", streamOptions);
    }
    JsonArray messages = new JsonArray();
    for (AgentMessage message : request.messages()) messages.add(messageJson(message));
    root.add("messages", messages);
    if (request.endpoint().nativeTools() && !request.tools().isEmpty()) {
      JsonArray tools = new JsonArray();
      for (ToolSpec spec : request.tools()) {
        JsonObject function = new JsonObject();
        function.addProperty("name", spec.name());
        function.addProperty("description", spec.description());
        function.add("parameters", gson.toJsonTree(spec.inputSchema()));
        if (request.endpoint().toolArgumentMode() == ToolArgumentMode.STRICT
            && spec.strictSchema()) {
          function.addProperty("strict", true);
        }
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        tools.add(tool);
      }
      root.add("tools", tools);
      root.addProperty("tool_choice", "auto");
      root.addProperty("parallel_tool_calls", false);
    }
    if (request.deepThinking()) root.addProperty("enable_thinking", true);
    return root;
  }

  private JsonObject messageJson(AgentMessage message) {
    JsonObject out = new JsonObject();
    out.addProperty("role", message.role().name().toLowerCase());
    out.addProperty("content", message.content());
    if (message.role() == AgentMessage.Role.TOOL) {
      out.addProperty("tool_call_id", message.toolCallId());
      if (!blank(message.name())) out.addProperty("name", message.name());
    }
    if (!message.toolCalls().isEmpty()) {
      JsonArray calls = new JsonArray();
      for (AgentToolCall call : message.toolCalls()) {
        JsonObject function = new JsonObject();
        function.addProperty("name", call.name());
        function.addProperty("arguments", gson.toJson(call.arguments().asMap()));
        JsonObject item = new JsonObject();
        item.addProperty("id", call.id());
        item.addProperty("type", "function");
        item.add("function", function);
        calls.add(item);
      }
      out.add("tool_calls", calls);
    }
    return out;
  }

  private void parseSse(
      Response response,
      ModelStreamObserver observer,
      AtomicBoolean terminal,
      long startedAt,
      int requestIndex,
      String model)
      throws IOException {
    StringBuilder content = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    Map<Integer, ToolCallBuilder> callBuilders = new LinkedHashMap<>();
    String finishReason = "";
    String loggedFinishReason = "";
    long finishReasonAt = 0L;
    long lastValidDeltaAt = 0L;
    long contentChars = 0L;
    long reasoningChars = 0L;
    long toolArgumentChars = 0L;
    boolean firstDeltaLogged = false;
    boolean doneReceived = false;
    ModelUsage usage = ModelUsage.UNKNOWN;
    int ignoredHeartbeatCount = 0;
    BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      if (!line.startsWith("data:")) continue;
      String data = line.substring(5).trim();
      if (data.isEmpty()) continue;
      if ("[DONE]".equals(data)) {
        doneReceived = true;
        long now = System.currentTimeMillis();
        logStream(
            requestIndex,
            "done",
            "elapsed_ms="
                + (now - startedAt)
                + ",finish_to_done_ms="
                + (finishReasonAt == 0L ? -1L : now - finishReasonAt)
                + ",last_delta_to_done_ms="
                + (lastValidDeltaAt == 0L ? -1L : now - lastValidDeltaAt)
                + ",ignored_heartbeat_count="
                + ignoredHeartbeatCount);
        break;
      }
      JsonElement parsed;
      try {
        parsed = JsonParser.parseString(data);
      } catch (RuntimeException ignored) {
        // Some compatible gateways send non-JSON data heartbeats. They are transport liveness,
        // not model progress, and must neither reset UI idle time nor fail the request.
        ignoredHeartbeatCount++;
        continue;
      }
      if (!parsed.isJsonObject()) {
        ignoredHeartbeatCount++;
        continue;
      }
      JsonObject event = parsed.getAsJsonObject();
      ModelUsage eventUsage = parseUsage(event.get("usage"));
      if (eventUsage.known()) usage = eventUsage;
      JsonArray choices = event.getAsJsonArray("choices");
      if (choices == null || choices.size() == 0) continue;
      JsonObject choice = choices.get(0).getAsJsonObject();
      finishReason = string(choice, "finish_reason", finishReason);
      JsonObject delta =
          choice.has("delta") && choice.get("delta").isJsonObject()
              ? choice.getAsJsonObject("delta")
              : choice.has("message") && choice.get("message").isJsonObject()
                  ? choice.getAsJsonObject("message")
                  : new JsonObject();
      String contentDelta = string(delta, "content", "");
      String reasoningDelta = string(delta, "reasoning_content", string(delta, "reasoning", ""));
      if (!contentDelta.isEmpty()) content.append(contentDelta);
      if (!reasoningDelta.isEmpty()) reasoning.append(reasoningDelta);
      List<ToolCallStreamDelta> streamCalls = new ArrayList<>();
      JsonArray toolCalls = delta.getAsJsonArray("tool_calls");
      if (toolCalls != null) {
        for (JsonElement element : toolCalls) {
          if (!element.isJsonObject()) continue;
          JsonObject item = element.getAsJsonObject();
          int index = item.has("index") ? item.get("index").getAsInt() : callBuilders.size();
          ToolCallBuilder builder =
              callBuilders.computeIfAbsent(index, key -> new ToolCallBuilder());
          String idDelta = string(item, "id", "");
          JsonObject function =
              item.has("function") && item.get("function").isJsonObject()
                  ? item.getAsJsonObject("function")
                  : new JsonObject();
          String nameDelta = string(function, "name", "");
          String argumentsDelta = jsonString(function, "arguments");
          builder.id = appendStable(builder.id, idDelta);
          builder.name = appendStable(builder.name, nameDelta);
          builder.arguments.append(argumentsDelta);
          streamCalls.add(
              new ToolCallStreamDelta(index, idDelta, nameDelta, argumentsDelta));
        }
      }
      ModelStreamDelta streamDelta =
          new ModelStreamDelta(contentDelta, reasoningDelta, streamCalls);
      if (!streamDelta.isEmpty()) {
        long now = System.currentTimeMillis();
        contentChars += contentDelta.length();
        reasoningChars += reasoningDelta.length();
        for (ToolCallStreamDelta call : streamCalls) {
          toolArgumentChars += call.arguments().length();
        }
        String detail =
            "type="
                + streamDeltaType(streamDelta)
                + ",content_chars="
                + contentChars
                + ",reasoning_chars="
                + reasoningChars
                + ",tool_arg_chars="
                + toolArgumentChars;
        if (!firstDeltaLogged) {
          firstDeltaLogged = true;
          logStream(
              requestIndex, "first_delta", "elapsed_ms=" + (now - startedAt) + "," + detail);
        } else if (lastValidDeltaAt > 0L
            && now - lastValidDeltaAt >= STREAM_GAP_LOG_THRESHOLD_MS) {
          logStream(
              requestIndex,
              "delta_gap",
              "gap_ms=" + (now - lastValidDeltaAt) + "," + detail);
        }
        lastValidDeltaAt = now;
        observer.onStreamDelta(streamDelta);
      }
      if (!finishReason.isEmpty() && !finishReason.equals(loggedFinishReason)) {
        loggedFinishReason = finishReason;
        finishReasonAt = System.currentTimeMillis();
        logStream(
            requestIndex,
            "finish_reason",
            "value="
                + compactLogValue(finishReason)
                + ",elapsed_ms="
                + (finishReasonAt - startedAt)
                + ",last_delta_to_finish_ms="
                + (lastValidDeltaAt == 0L ? -1L : finishReasonAt - lastValidDeltaAt));
      }
    }
    if (!doneReceived) {
      long now = System.currentTimeMillis();
      logStream(
          requestIndex,
          "eof",
          "elapsed_ms="
              + (now - startedAt)
              + ",finish_to_eof_ms="
              + (finishReasonAt == 0L ? -1L : now - finishReasonAt)
              + ",last_delta_to_eof_ms="
              + (lastValidDeltaAt == 0L ? -1L : now - lastValidDeltaAt)
              + ",ignored_heartbeat_count="
              + ignoredHeartbeatCount);
    }
    if (terminal.compareAndSet(false, true)) {
      BuiltToolCalls calls = buildCalls(callBuilders);
      logResponse(
          content.toString(),
          reasoning.toString(),
          finishReason,
          calls.valid,
          calls.invalid,
          startedAt,
          requestIndex,
          model);
      logUsage(requestIndex, model, usage);
      observer.onComplete(
          new ModelResponse(
              content.toString(), finishReason, calls.valid, calls.invalid, usage));
    }
  }

  private void parseJsonResponse(
      String raw,
      ModelStreamObserver observer,
      AtomicBoolean terminal,
      long startedAt,
      int requestIndex,
      String model) {
    JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
    ModelUsage usage = parseUsage(root.get("usage"));
    JsonArray choices = root.getAsJsonArray("choices");
    if (choices == null || choices.size() == 0) {
      throw new IllegalArgumentException("模型响应缺少 choices");
    }
    JsonObject choice = choices.get(0).getAsJsonObject();
    JsonObject payload =
        choice.has("message") && choice.get("message").isJsonObject()
            ? choice.getAsJsonObject("message")
            : choice.has("delta") && choice.get("delta").isJsonObject()
                ? choice.getAsJsonObject("delta")
                : new JsonObject();
    String content = string(payload, "content", "");
    String reasoning = string(payload, "reasoning_content", string(payload, "reasoning", ""));
    Map<Integer, ToolCallBuilder> builders = new LinkedHashMap<>();
    List<ToolCallStreamDelta> streamCalls = new ArrayList<>();
    JsonArray calls = payload.getAsJsonArray("tool_calls");
    if (calls != null) {
      for (int index = 0; index < calls.size(); index++) {
        JsonObject item = calls.get(index).getAsJsonObject();
        ToolCallBuilder builder = new ToolCallBuilder();
        builder.id = string(item, "id", "call-" + index);
        JsonObject function =
            item.has("function") && item.get("function").isJsonObject()
                ? item.getAsJsonObject("function")
                : new JsonObject();
        builder.name = string(function, "name", "");
        String arguments = jsonString(function, "arguments");
        builder.arguments.append(arguments);
        builders.put(index, builder);
        streamCalls.add(
            new ToolCallStreamDelta(index, builder.id, builder.name, arguments));
      }
    }
    ModelStreamDelta streamDelta = new ModelStreamDelta(content, reasoning, streamCalls);
    if (!streamDelta.isEmpty()) observer.onStreamDelta(streamDelta);
    if (terminal.compareAndSet(false, true)) {
      String finishReason = string(choice, "finish_reason", "stop");
      BuiltToolCalls toolCalls = buildCalls(builders);
      logResponse(
          content,
          reasoning,
          finishReason,
          toolCalls.valid,
          toolCalls.invalid,
          startedAt,
          requestIndex,
          model);
      logUsage(requestIndex, model, usage);
      observer.onComplete(
          new ModelResponse(content, finishReason, toolCalls.valid, toolCalls.invalid, usage));
    }
  }

  private void logUsage(int requestIndex, String model, ModelUsage usage) {
    if (usage == null || !usage.known()) return;
    log(
        "model_usage",
        "[模型用量][request="
            + requestIndex
            + "][model="
            + model
            + "] input="
            + usage.inputTokens()
            + ",cached="
            + usage.cachedTokens()
            + ",uncached="
            + usage.uncachedTokens()
            + ",cached_percent="
            + usage.cachedPercent()
            + ",output="
            + usage.outputTokens()
            + ",total="
            + usage.totalTokens());
  }

  private static ModelUsage parseUsage(JsonElement raw) {
    if (raw == null || !raw.isJsonObject()) return ModelUsage.UNKNOWN;
    JsonObject usage = raw.getAsJsonObject();
    long input = firstLong(usage, "prompt_tokens", "input_tokens");
    long output = firstLong(usage, "completion_tokens", "output_tokens");
    long total = firstLong(usage, "total_tokens");
    long cached = nestedLong(usage, "prompt_tokens_details", "cached_tokens");
    if (cached < 0L) cached = nestedLong(usage, "input_tokens_details", "cached_tokens");
    if (cached < 0L) {
      cached = firstLong(
          usage, "prompt_cache_hit_tokens", "cached_tokens", "cache_read_input_tokens");
    }
    return new ModelUsage(input, cached, output, total);
  }

  private static long nestedLong(JsonObject source, String objectKey, String valueKey) {
    return source != null && source.has(objectKey) && source.get(objectKey).isJsonObject()
        ? firstLong(source.getAsJsonObject(objectKey), valueKey) : -1L;
  }

  private static long firstLong(JsonObject source, String... keys) {
    if (source == null) return -1L;
    for (String key : keys) {
      try {
        if (source.has(key) && !source.get(key).isJsonNull()) return source.get(key).getAsLong();
      } catch (RuntimeException ignored) {
      }
    }
    return -1L;
  }

  private static boolean unsupportedUsageOption(String detail) {
    String value = detail == null ? "" : detail.toLowerCase();
    return (value.contains("stream_options") || value.contains("include_usage"))
        && (value.contains("unknown")
            || value.contains("unsupported")
            || value.contains("not support")
            || value.contains("unrecognized"));
  }

  private void logResponse(
      String content,
      String reasoning,
      String finishReason,
      List<AgentToolCall> toolCalls,
      List<InvalidToolCall> invalidToolCalls,
      long startedAt,
      int requestIndex,
      String model) {
    log(
        "model_response",
        "[模型响应][request="
            + requestIndex
            + "][model="
            + model
            + "][finish_reason="
            + finishReason
            + "][elapsed_ms="
            + (System.currentTimeMillis() - startedAt)
            + "][content_chars="
            + content.length()
            + "][reasoning_chars="
            + reasoning.length()
            + "][tool_calls="
            + (toolCalls.size() + invalidToolCalls.size())
            + "][invalid_tool_calls="
            + invalidToolCalls.size()
            + "]");
    log(
        "model_response",
        "[本轮模型返回][request=" + requestIndex + "][content]" + content);
    if (!reasoning.isEmpty()) {
      log(
          "model_response",
          "[本轮模型返回][request=" + requestIndex + "][reasoning]" + reasoning);
    }
    for (AgentToolCall toolCall : toolCalls) {
      log(
          "model_response",
          "[本轮模型返回][request="
              + requestIndex
              + "][tools][toolName="
              + toolCall.name()
              + "]"
              + readableJson(toolCall.arguments().asMap()));
    }
    for (InvalidToolCall toolCall : invalidToolCalls) {
      log(
          "model_response",
          "[本轮模型返回][request="
              + requestIndex
              + "][invalid_tool_arguments][toolName="
              + toolCall.name()
              + "][offset="
              + toolCall.errorOffset()
              + "][escape="
              + compactLogValue(toolCall.invalidEscape())
              + "][argument_chars="
              + toolCall.argumentChars()
              + "]");
    }
  }

  private void logError(
      Throwable error, long startedAt, int requestIndex, String model) {
    String type = error == null ? "Unknown" : error.getClass().getSimpleName();
    String message = error == null || error.getMessage() == null ? "" : error.getMessage();
    log(
        "model_error",
        "[模型请求失败][request="
            + requestIndex
            + "][model="
            + model
            + "][elapsed_ms="
            + (System.currentTimeMillis() - startedAt)
            + "][type="
            + type
            + "] message="
            + message);
  }

  private void logStream(int requestIndex, String event, String detail) {
    log(
        "model_stream",
        "[模型流][request="
            + requestIndex
            + "][event="
            + event
            + "]"
            + (blank(detail) ? "" : " " + detail));
  }

  private static String streamDeltaType(ModelStreamDelta delta) {
    int types = 0;
    String value = "";
    if (delta != null && !delta.reasoning().isEmpty()) {
      types++;
      value = "reasoning";
    }
    if (delta != null && !delta.content().isEmpty()) {
      types++;
      value = "content";
    }
    if (delta != null && !delta.toolCalls().isEmpty()) {
      types++;
      value = "tool_args";
    }
    return types > 1 ? "mixed" : value.isEmpty() ? "empty" : value;
  }

  private static String compactContentType(String contentType) {
    if (contentType == null) return "";
    int separator = contentType.indexOf(';');
    return (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
  }

  private static String compactLogValue(String value) {
    String safe = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    return safe.length() <= 64 ? safe : safe.substring(0, 64);
  }

  private synchronized void logRequest(
      ModelRequest request, String url, int requestIndex, int serializedChars) {
    List<AgentMessage> messages = request.messages();
    int start = 0;
    if (requestIndex > 1 && messages.size() >= previousMessageCount) {
      start = previousMessageCount;
    }
    if (start >= messages.size() && !messages.isEmpty()) {
      start = messages.size() - 1;
    }
    previousMessageCount = messages.size();

    StringBuilder output = new StringBuilder();
    output
        .append("[模型请求][request=")
        .append(requestIndex)
        .append("][stage=")
        .append(requestIndex == 1 ? "initial" : "continue")
        .append("][model=")
        .append(request.endpoint().modelId())
        .append("]\nurl=")
        .append(url)
        .append("\nmessages=")
        .append(messages.size())
        .append(", new_messages=")
        .append(Math.max(0, messages.size() - start))
        .append(", tools=")
        .append(request.tools().size())
        .append(", deep_thinking=")
        .append(request.deepThinking())
        .append(", serialized_chars=")
        .append(Math.max(0, serializedChars));
    if (!request.tools().isEmpty()) {
      output.append("\ntool_names=");
      for (int index = 0; index < request.tools().size(); index++) {
        if (index > 0) output.append(',');
        output.append(request.tools().get(index).name());
      }
    }
    for (int index = start; index < messages.size(); index++) {
      AgentMessage message = messages.get(index);
      output
          .append("\n\n[message=")
          .append(index)
          .append("][role=")
          .append(message.role().name().toLowerCase())
          .append(']');
      if (!blank(message.name())) output.append("[name=").append(message.name()).append(']');
      if (!blank(message.toolCallId())) {
        output.append("[tool_call_id=").append(message.toolCallId()).append(']');
      }
      if (!message.content().isEmpty()) output.append('\n').append(message.content());
      for (AgentToolCall toolCall : message.toolCalls()) {
        output
            .append("\n[tool_call][toolName=")
            .append(toolCall.name())
            .append("]")
            .append(readableJson(toolCall.arguments().asMap()));
      }
    }
    log("model_request", output.toString());
  }

  private String readableJson(Object value) {
    return prettyGson
        .toJson(value)
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", "\t");
  }

  private void log(String event, String message) {
    try {
      logger.log(event, message);
    } catch (RuntimeException ignored) {
      // Diagnostics must never break a model request.
    }
  }

  private BuiltToolCalls buildCalls(Map<Integer, ToolCallBuilder> builders) {
    List<Map.Entry<Integer, ToolCallBuilder>> entries = new ArrayList<>(builders.entrySet());
    entries.sort(Comparator.comparingInt(Map.Entry::getKey));
    List<AgentToolCall> calls = new ArrayList<>();
    List<InvalidToolCall> invalidCalls = new ArrayList<>();
    for (Map.Entry<Integer, ToolCallBuilder> entry : entries) {
      ToolCallBuilder b = entry.getValue();
      String raw = b.arguments.toString();
      ParsedToolArguments parsed = parseToolArguments(raw);
      String id = blank(b.id) ? "call-" + entry.getKey() : b.id;
      if (parsed.error != null) {
        invalidCalls.add(
            new InvalidToolCall(
                id,
                b.name,
                parsed.error.message,
                parsed.error.invalidEscape,
                parsed.error.offset,
                raw.length()));
      } else {
        calls.add(new AgentToolCall(id, b.name, new ToolArguments(parsed.arguments)));
      }
    }
    return new BuiltToolCalls(calls, invalidCalls);
  }

  @SuppressWarnings("unchecked")
  private ParsedToolArguments parseToolArguments(String raw) {
    String source = raw == null ? "" : raw.trim();
    if (source.isEmpty()) {
      return ParsedToolArguments.invalid("参数为空", "", 0);
    }
    try {
      JsonReader reader = new JsonReader(new StringReader(source));
      reader.setLenient(false);
      JsonElement parsed = Streams.parse(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        return ParsedToolArguments.invalid("参数包含 JSON 对象之外的多余内容", "", -1);
      }
      if (!parsed.isJsonObject()) {
        return ParsedToolArguments.invalid("参数必须是 JSON 对象", "", 0);
      }
      Map<String, Object> values = gson.fromJson(parsed, Map.class);
      return ParsedToolArguments.valid(values == null ? Collections.emptyMap() : values);
    } catch (Exception error) {
      InvalidEscape invalidEscape = firstInvalidEscape(source);
      int offset = invalidEscape == null ? errorOffset(error) : invalidEscape.offset;
      String token = invalidEscape == null ? "" : invalidEscape.token;
      return ParsedToolArguments.invalid(compactParseError(error), token, offset);
    }
  }

  private static InvalidEscape firstInvalidEscape(String source) {
    for (int index = 0; index < source.length(); index++) {
      if (source.charAt(index) != '\\') continue;
      if (index + 1 >= source.length()) return new InvalidEscape("\\<eof>", index);
      char escaped = source.charAt(index + 1);
      if ("\"\\/bfnrt".indexOf(escaped) >= 0) {
        index++;
        continue;
      }
      if (escaped != 'u') return new InvalidEscape("\\" + escaped, index);
      if (index + 5 >= source.length()) {
        String token = index + 2 < source.length() && source.charAt(index + 2) == '{'
            ? "\\u{" : "\\u";
        return new InvalidEscape(token, index);
      }
      for (int digit = index + 2; digit <= index + 5; digit++) {
        if (Character.digit(source.charAt(digit), 16) < 0) {
          String token = source.charAt(index + 2) == '{' ? "\\u{" : "\\u";
          return new InvalidEscape(token, index);
        }
      }
      index += 5;
    }
    return null;
  }

  private static int errorOffset(Exception error) {
    String message = error == null ? "" : String.valueOf(error.getMessage());
    Matcher matcher = JSON_COLUMN.matcher(message);
    if (!matcher.find()) return -1;
    try {
      return Math.max(0, Integer.parseInt(matcher.group(1)) - 1);
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static String compactParseError(Exception error) {
    String message = error == null ? "" : String.valueOf(error.getMessage()).trim();
    int at = message.indexOf(" at line ");
    if (at > 0) message = message.substring(0, at);
    return message.isEmpty() ? "无法解析参数" : message;
  }

  private static String chatUrl(String baseUrl) {
    String value = baseUrl.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value.endsWith("/chat/completions") ? value : value + "/chat/completions";
  }

  private static String string(JsonObject object, String key, String fallback) {
    if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
    try {
      return object.get(key).getAsString();
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static String appendStable(String current, String delta) {
    if (blank(delta)) return current;
    if (blank(current)) return delta;
    if (current.equals(delta) || current.endsWith(delta) || current.startsWith(delta))
      return current;
    if (delta.startsWith(current)) return delta;
    return current + delta;
  }

  private static String jsonString(JsonObject object, String key) {
    if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
    JsonElement value = object.get(key);
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
      return value.getAsString();
    return value.toString();
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String safeDetail(String value) {
    if (blank(value)) return "";
    String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
    return ": " + compact.substring(0, Math.min(compact.length(), 300));
  }

  private static final class BuiltToolCalls {
    final List<AgentToolCall> valid;
    final List<InvalidToolCall> invalid;

    BuiltToolCalls(List<AgentToolCall> valid, List<InvalidToolCall> invalid) {
      this.valid = Collections.unmodifiableList(new ArrayList<>(valid));
      this.invalid = Collections.unmodifiableList(new ArrayList<>(invalid));
    }
  }

  private static final class ParsedToolArguments {
    final Map<String, Object> arguments;
    final ToolArgumentError error;

    private ParsedToolArguments(Map<String, Object> arguments, ToolArgumentError error) {
      this.arguments = arguments;
      this.error = error;
    }

    static ParsedToolArguments valid(Map<String, Object> arguments) {
      return new ParsedToolArguments(arguments, null);
    }

    static ParsedToolArguments invalid(String message, String invalidEscape, int offset) {
      return new ParsedToolArguments(
          Collections.emptyMap(), new ToolArgumentError(message, invalidEscape, offset));
    }
  }

  private static final class ToolArgumentError {
    final String message;
    final String invalidEscape;
    final int offset;

    ToolArgumentError(String message, String invalidEscape, int offset) {
      this.message = message == null ? "" : message;
      this.invalidEscape = invalidEscape == null ? "" : invalidEscape;
      this.offset = offset;
    }
  }

  private static final class InvalidEscape {
    final String token;
    final int offset;

    InvalidEscape(String token, int offset) {
      this.token = token;
      this.offset = offset;
    }
  }

  private static final class ToolCallBuilder {
    String id = "";
    String name = "";
    final StringBuilder arguments = new StringBuilder();
  }
}
