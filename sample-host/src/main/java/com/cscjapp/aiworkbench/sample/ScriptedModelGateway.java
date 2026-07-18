package com.cscjapp.aiworkbench.sample;

import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.core.AgentMessage;
import com.cscjapp.aiworkbench.core.AgentToolCall;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.ModelRequest;
import com.cscjapp.aiworkbench.core.ModelResponse;
import com.cscjapp.aiworkbench.core.ModelStreamDelta;
import com.cscjapp.aiworkbench.core.ModelStreamObserver;
import com.cscjapp.aiworkbench.core.ToolCallStreamDelta;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Deterministic offline gateway used by the standalone SDK Playground. */
final class ScriptedModelGateway implements ModelGateway {
  private static final ScheduledExecutorService EXECUTOR =
      Executors.newScheduledThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "aiw-playground-model");
            thread.setDaemon(true);
            return thread;
          });

  private final AtomicLong callIds = new AtomicLong();
  private final Consumer<String> logger;
  private final Supplier<String> generatedPathSupplier;

  ScriptedModelGateway(Consumer<String> logger) {
    this(logger, null);
  }

  ScriptedModelGateway(Consumer<String> logger, Supplier<String> generatedPathSupplier) {
    this.logger = logger == null ? ignored -> {} : logger;
    this.generatedPathSupplier =
        generatedPathSupplier == null
            ? () -> "generated-" + callIds.incrementAndGet() + ".txt"
            : generatedPathSupplier;
  }

  @Override
  public Cancellable stream(ModelRequest request, ModelStreamObserver observer) {
    ScriptRun run = new ScriptRun(observer);
    String demand = latestDemand(request);
    AgentMessage last = lastMessage(request);
    logger.accept(
        "离线请求：messages="
            + request.messages().size()
            + "，tools="
            + request.tools().size()
            + "，demand="
            + abbreviate(demand, 80));

    if (hasTool(request, "finalize_task")) {
      ToolPlan codePlan = codeAgentPlan(last);
      if (!request.endpoint().nativeTools()) {
        emitLegacy(run, codePlan);
      } else {
        emitNativeTool(run, codePlan);
      }
      return run;
    }

    if (last != null && last.role() == AgentMessage.Role.TOOL) {
      emitText(
          run,
          request.deepThinking() ? "已检查工具执行结果，正在组织最终回复。" : "",
          "离线 Playground 已完成工具调用，结果已经记录到工作台和事件日志中。",
          18L);
      return run;
    }

    String normalized = demand.toLowerCase(Locale.ROOT);
    if (normalized.contains("模拟错误") || normalized.contains("error")) {
      run.schedule(
          80L,
          () -> {
            logger.accept("按场景返回模拟网络错误");
            observer.onError(new IllegalStateException("Playground 模拟模型错误"));
          });
      return run;
    }
    if (normalized.contains("模拟超时") || normalized.contains("timeout")) {
      run.schedule(
          40L,
          () ->
              observer.onStreamDelta(
                  ModelStreamDelta.text("", "请求已进入模拟慢响应，可点击发送按钮取消。")));
      run.schedule(
          4000L,
          () -> observer.onError(new IllegalStateException("Playground 模拟请求超时")));
      return run;
    }
    if (normalized.contains("长文本") || normalized.contains("20000")) {
      StringBuilder content = new StringBuilder(20_500);
      int line = 1;
      while (content.length() < 20_000) {
        content.append(
            String.format(Locale.ROOT, "第 %04d 行：Playground 流式正文性能验证。\\n", line++));
      }
      emitText(
          run,
          "正在生成长文本并验证按帧合并刷新。",
          content.substring(0, 20_000),
          2L);
      return run;
    }

    ToolPlan plan = toolPlan(normalized);
    if (!request.endpoint().nativeTools()) {
      emitLegacy(run, plan);
      return run;
    }
    emitNativeTool(run, plan);
    return run;
  }

  private void emitText(ScriptRun run, String reasoning, String content, long intervalMs) {
    long offset = 0L;
    for (String chunk : chunks(reasoning, 7)) {
      final String value = chunk;
      run.schedule(
          offset,
          () -> run.observer.onStreamDelta(ModelStreamDelta.text("", value)));
      offset += intervalMs;
    }
    for (String chunk : chunks(content, 64)) {
      final String value = chunk;
      run.schedule(
          offset,
          () -> run.observer.onStreamDelta(ModelStreamDelta.text(value, "")));
      offset += intervalMs;
    }
    run.schedule(
        offset + intervalMs,
        () -> {
          logger.accept("离线文本响应完成：content=" + content.length());
          run.observer.onComplete(new ModelResponse(content, "stop", Collections.emptyList()));
        });
  }

  private void emitNativeTool(ScriptRun run, ToolPlan plan) {
    String id = "playground-call-" + callIds.incrementAndGet();
    String json = plan.jsonArguments();
    long offset = 0L;
    String reasoning = "正在分析需求，并准备调用 " + plan.name + " 工具。";
    for (String chunk : chunks(reasoning, 6)) {
      final String value = chunk;
      run.schedule(
          offset,
          () -> run.observer.onStreamDelta(ModelStreamDelta.text("", value)));
      offset += 12L;
    }
    List<String> arguments = chunks(json, plan.large ? 256 : 12);
    for (int index = 0; index < arguments.size(); index++) {
      final int fragmentIndex = index;
      final String fragment = arguments.get(index);
      run.schedule(
          offset,
          () ->
              run.observer.onStreamDelta(
                  new ModelStreamDelta(
                      "",
                      "",
                      Collections.singletonList(
                          new ToolCallStreamDelta(
                              0,
                              fragmentIndex == 0 ? id : "",
                              fragmentIndex == 0 ? plan.name : "",
                              fragment)))));
      offset += plan.large ? 2L : 16L;
    }
    run.schedule(
        offset + 12L,
        () -> {
          logger.accept("离线 Native 工具参数接收完成：" + plan.name + " / " + json.length());
          AgentToolCall call = new AgentToolCall(id, plan.name, new ToolArguments(plan.arguments));
          run.observer.onComplete(
              new ModelResponse("", "tool_calls", Collections.singletonList(call)));
        });
  }

  private void emitLegacy(ScriptRun run, ToolPlan plan) {
    String content =
        "{\"next_action\":{\"tool\":\""
            + plan.name
            + "\",\"args\":"
            + plan.jsonArguments()
            + "}}";
    long offset = 0L;
    for (String chunk : chunks(content, plan.large ? 256 : 9)) {
      final String value = chunk;
      run.schedule(
          offset,
          () -> run.observer.onStreamDelta(ModelStreamDelta.text(value, "")));
      offset += plan.large ? 2L : 16L;
    }
    run.schedule(
        offset + 12L,
        () -> {
          logger.accept("离线 Legacy 工具协议接收完成：" + plan.name);
          run.observer.onComplete(new ModelResponse(content, "stop", Collections.emptyList()));
        });
  }

  private ToolPlan toolPlan(String demand) {
    if (demand.contains("长参数") || demand.contains("100000")) {
      return ToolPlan.echo(repeatToLength("Playground-参数流-", 100_000), true);
    }
    if (demand.contains("冲突") || demand.contains("覆盖")) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("path", "demo.txt");
      values.put("content", "该内容来自文件冲突场景，用户选择后写入。\\n");
      return new ToolPlan("create_file", values, false);
    }
    if (demand.contains("创建") || demand.contains("create")) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("path", generatedPathSupplier.get());
      values.put("content", "AI Workbench Playground 离线创建成功。\\n");
      return new ToolPlan("create_file", values, false);
    }
    if (demand.contains("替换") || demand.contains("replace")) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("path", "demo.txt");
      values.put("old", "Playground 初始文本：等待 AI 修改。");
      values.put("new", "Playground 已完成 search_replace 修改。");
      return new ToolPlan("search_replace", values, false);
    }
    if (demand.contains("读取") || demand.contains("read")) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("path", "README.md");
      return new ToolPlan("read_file", values, false);
    }
    return ToolPlan.echo(
        "离线 echo 已收到需求：" + (demand.isEmpty() ? "请测试 Playground" : demand), false);
  }

  private ToolPlan codeAgentPlan(AgentMessage last) {
    String previous =
        last != null && last.role() == AgentMessage.Role.TOOL ? last.name() : "";
    Map<String, Object> values = new LinkedHashMap<>();
    if (previous.isEmpty()) {
      values.put("goal", "完成 Playground 通用 Code Agent 闭环");
      values.put("quality_mode", "standard");
      values.put(
          "verification_plan",
          Collections.singletonList("verify_workspace 检查真实文件结果"));
      return new ToolPlan("plan_task", values, false);
    }
    if ("plan_task".equals(previous)) {
      values.put("path", "code-agent-demo.txt");
      return new ToolPlan("read_file", values, false);
    }
    if ("read_file".equals(previous)) {
      values.put("path", "code-agent-demo.txt");
      values.put("content", "Code Agent 闭环修改完成。\\n");
      return new ToolPlan("rewrite", values, false);
    }
    if ("rewrite".equals(previous)) {
      values.put("path", "code-agent-demo.txt");
      return new ToolPlan("verify_workspace", values, false);
    }
    if ("verify_workspace".equals(previous)) {
      values.put("against_quality_bar", true);
      values.put("quality_mode", "standard");
      values.put("passed", true);
      values.put("blocking_gaps", Collections.emptyList());
      values.put("minimal_version_risk", false);
      values.put(
          "evidence",
          Collections.singletonList("verify_workspace 已读取真实文件并通过"));
      return new ToolPlan("quality_review", values, false);
    }
    values.put("status", "completed");
    values.put("completion_type", "feature_integration");
    values.put("summary", "Playground 已完成通用 Code Agent 全流程闭环。");
    values.put(
        "changed_files", Collections.singletonList("code-agent-demo.txt"));
    values.put(
        "verification", Collections.singletonList("verify_workspace passed=true"));
    return new ToolPlan("finalize_task", values, false);
  }

  private static boolean hasTool(ModelRequest request, String name) {
    return request.tools().stream().anyMatch(tool -> name.equals(tool.name()));
  }

  private static String latestDemand(ModelRequest request) {
    List<AgentMessage> messages = request.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      AgentMessage message = messages.get(i);
      if (message.role() == AgentMessage.Role.USER) return message.content();
    }
    return "";
  }

  private static AgentMessage lastMessage(ModelRequest request) {
    List<AgentMessage> messages = request.messages();
    return messages.isEmpty() ? null : messages.get(messages.size() - 1);
  }

  private static List<String> chunks(String value, int size) {
    if (value == null || value.isEmpty()) return Collections.emptyList();
    List<String> result = new ArrayList<>();
    for (int i = 0; i < value.length(); i += size) {
      result.add(value.substring(i, Math.min(value.length(), i + size)));
    }
    return result;
  }

  private static String repeatToLength(String value, int length) {
    StringBuilder result = new StringBuilder(length);
    while (result.length() < length) result.append(value);
    return result.substring(0, length);
  }

  private static String abbreviate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }

  private static final class ScriptRun implements Cancellable {
    final ModelStreamObserver observer;
    final AtomicBoolean cancelled = new AtomicBoolean();
    final List<ScheduledFuture<?>> futures = Collections.synchronizedList(new ArrayList<>());

    ScriptRun(ModelStreamObserver observer) {
      this.observer = observer;
    }

    void schedule(long delayMs, Runnable action) {
      ScheduledFuture<?> future =
          EXECUTOR.schedule(
              () -> {
                if (!cancelled.get()) action.run();
              },
              Math.max(0L, delayMs),
              TimeUnit.MILLISECONDS);
      futures.add(future);
    }

    @Override
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) return;
      synchronized (futures) {
        for (ScheduledFuture<?> future : futures) future.cancel(true);
        futures.clear();
      }
    }
  }

  private static final class ToolPlan {
    final String name;
    final Map<String, Object> arguments;
    final boolean large;

    ToolPlan(String name, Map<String, Object> arguments, boolean large) {
      this.name = name;
      this.arguments = arguments;
      this.large = large;
    }

    static ToolPlan echo(String text, boolean large) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("text", text);
      return new ToolPlan("echo", values, large);
    }

    String jsonArguments() {
      return json(arguments);
    }

    private static String json(Object value) {
      if (value == null) return "null";
      if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
      if (value instanceof Map) {
        StringBuilder output = new StringBuilder("{");
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
          if (output.length() > 1) output.append(',');
          output
              .append('"')
              .append(escape(String.valueOf(entry.getKey())))
              .append("\":")
              .append(json(entry.getValue()));
        }
        return output.append('}').toString();
      }
      if (value instanceof Iterable) {
        StringBuilder output = new StringBuilder("[");
        for (Object item : (Iterable<?>) value) {
          if (output.length() > 1) output.append(',');
          output.append(json(item));
        }
        return output.append(']').toString();
      }
      return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String escape(String value) {
      return value
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
          .replace("\n", "\\n")
          .replace("\r", "\\r")
          .replace("\t", "\\t");
    }
  }
}
