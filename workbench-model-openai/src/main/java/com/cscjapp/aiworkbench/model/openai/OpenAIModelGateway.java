package com.cscjapp.aiworkbench.model.openai;

import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolSpec;
import com.cscjapp.aiworkbench.core.AgentMessage;
import com.cscjapp.aiworkbench.core.AgentToolCall;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.ModelRequest;
import com.cscjapp.aiworkbench.core.ModelResponse;
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
    JsonObject body = buildRequest(modelRequest);
    String requestJson = gson.toJson(body);
    String url = chatUrl(endpoint.baseUrl());
    logRequest(modelRequest, url, requestIndex);
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
    AtomicBoolean terminal = new AtomicBoolean();
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
                throw new IOException("模型请求失败 HTTP " + response.code() + safeDetail(detail));
              }
              String contentType = response.header("Content-Type", "");
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
    return call::cancel;
  }

  private JsonObject buildRequest(ModelRequest request) {
    JsonObject root = new JsonObject();
    root.addProperty("model", request.endpoint().modelId());
    root.addProperty("stream", true);
    root.addProperty("temperature", request.endpoint().temperature());
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
    BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      if (!line.startsWith("data:")) continue;
      String data = line.substring(5).trim();
      if (data.isEmpty()) continue;
      if ("[DONE]".equals(data)) break;
      JsonElement parsed = JsonParser.parseString(data);
      if (!parsed.isJsonObject()) continue;
      JsonArray choices = parsed.getAsJsonObject().getAsJsonArray("choices");
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
      if (!streamDelta.isEmpty()) observer.onStreamDelta(streamDelta);
    }
    if (terminal.compareAndSet(false, true)) {
      List<AgentToolCall> calls = buildCalls(callBuilders);
      logResponse(
          content.toString(),
          reasoning.toString(),
          finishReason,
          calls,
          startedAt,
          requestIndex,
          model);
      observer.onComplete(new ModelResponse(content.toString(), finishReason, calls));
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
      List<AgentToolCall> toolCalls = buildCalls(builders);
      logResponse(
          content, reasoning, finishReason, toolCalls, startedAt, requestIndex, model);
      observer.onComplete(new ModelResponse(content, finishReason, toolCalls));
    }
  }

  private void logResponse(
      String content,
      String reasoning,
      String finishReason,
      List<AgentToolCall> toolCalls,
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
            + toolCalls.size()
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

  private synchronized void logRequest(
      ModelRequest request, String url, int requestIndex) {
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
        .append(request.deepThinking());
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

  private List<AgentToolCall> buildCalls(Map<Integer, ToolCallBuilder> builders) {
    List<Map.Entry<Integer, ToolCallBuilder>> entries = new ArrayList<>(builders.entrySet());
    entries.sort(Comparator.comparingInt(Map.Entry::getKey));
    List<AgentToolCall> calls = new ArrayList<>();
    for (Map.Entry<Integer, ToolCallBuilder> entry : entries) {
      ToolCallBuilder b = entry.getValue();
      Map<String, Object> args = Collections.emptyMap();
      if (b.arguments.length() > 0) {
        try {
          args = gson.fromJson(b.arguments.toString(), Map.class);
        } catch (Exception ignored) {
          args = Collections.singletonMap("__raw_arguments", b.arguments.toString());
        }
      }
      String id = blank(b.id) ? "call-" + entry.getKey() : b.id;
      calls.add(new AgentToolCall(id, b.name, new ToolArguments(args)));
    }
    return calls;
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

  private static final class ToolCallBuilder {
    String id = "";
    String name = "";
    final StringBuilder arguments = new StringBuilder();
  }
}
