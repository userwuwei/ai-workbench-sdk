package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class AgentEngine implements Cancellable {
  private final WorkbenchDefinition definition;
  private final ModelGateway gateway;
  private volatile ModelEndpoint endpoint;
  private final ToolRegistry registry;
  private final List<ToolPolicy> toolPolicies;
  private final ToolDispatcher dispatcher;
  private final ValidationRunner validation;
  private final PromptComposer prompts = new PromptComposer();
  private final List<AgentMessage> messages = new ArrayList<>();
  private final List<ToolResult> evidence = new ArrayList<>();
  private final Executor executor;
  private final String sessionId, workspaceId;
  private volatile boolean deepThinking;
  private final int maxRounds;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicLong runGeneration = new AtomicLong();
  private final Set<Long> finishedRuns = ConcurrentHashMap.newKeySet();
  private volatile Cancellable active = Cancellable.NONE;
  private volatile AgentRunContext activeRunContext;

  public AgentEngine(
      WorkbenchDefinition d,
      ModelGateway g,
      ModelEndpoint e,
      UserDecisionService decisions,
      Executor executor,
      String sessionId,
      String workspaceId,
      boolean deepThinking,
      int maxRounds) {
    definition = d;
    gateway = g;
    endpoint = e;
    this.executor = executor;
    this.sessionId = sessionId;
    this.workspaceId = workspaceId;
    this.deepThinking = deepThinking;
    this.maxRounds = Math.max(1, maxRounds);
    registry = new ToolRegistry(d.tools());
    List<ToolPolicy> configuredPolicies = d.toolPolicies();
    toolPolicies =
        configuredPolicies == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(configuredPolicies));
    dispatcher =
        new ToolDispatcher(
            registry,
            toolPolicies,
            new DefaultToolContext(sessionId, workspaceId, executor, decisions, d.host()));
    validation = new ValidationRunner(d.validators());
  }

  public void updateEndpoint(ModelEndpoint endpoint) {
    if (endpoint != null) this.endpoint = endpoint;
  }

  public void setDeepThinking(boolean deepThinking) {
    this.deepThinking = deepThinking;
  }

  public synchronized void restoreMessages(List<AgentMessage> restored) {
    messages.clear();
    messages.addAll(AgentHistory.sanitize(restored));
  }

  public synchronized List<AgentMessage> messages() {
    return Collections.unmodifiableList(new ArrayList<>(messages));
  }

  public Cancellable submit(String demand, AgentObserver o) {
    AgentRunContext previousRun = activeRunContext;
    if (previousRun != null) finishRun(previousRun.runId(), "superseded");
    long runId = runGeneration.incrementAndGet();
    cancelled.set(false);
    AgentRunContext runContext = new AgentRunContext(runId, sessionId, workspaceId, demand);
    activeRunContext = runContext;
    dispatcher.onRunStarted(runContext);
    executor.execute(
        () -> {
          if (!isActive(runId)) return;
          try {
            Map<String, Object> promptRuntime = new LinkedHashMap<>();
            promptRuntime.put("native_tools", endpoint != null && endpoint.nativeTools());
            promptRuntime.put("deep_thinking", deepThinking);
            promptRuntime.put("model_id", endpoint == null ? "" : endpoint.modelId());
            String system =
                prompts.compose(
                    definition, new PromptContext(workspaceId, demand, promptRuntime));
            synchronized (this) {
              List<AgentMessage> compacted = AgentHistory.compactCompletedTasks(messages);
              messages.clear();
              messages.addAll(compacted);
              evidence.clear();
              if (messages.isEmpty()) messages.add(AgentMessage.system(system));
              else if (messages.get(0).role() == AgentMessage.Role.SYSTEM)
                messages.set(0, AgentMessage.system(system));
              else messages.add(0, AgentMessage.system(system));
              messages.add(AgentMessage.user(demand));
            }
            round(demand, o, 0, runId);
          } catch (Throwable t) {
            if (isActive(runId)) {
              finishRun(runId, "error");
              o.onError(t);
            }
          }
        });
    return this;
  }

  private void round(String demand, AgentObserver o, int n, long runId) {
    if (!isActive(runId)) return;
    if (n >= maxRounds) {
      finishRun(runId, "max_rounds");
      o.onError(new IllegalStateException("Agent 超过最大工具轮次 " + maxRounds));
      return;
    }
    o.onState("model_running");
    ModelRequest req;
    Set<String> selectedToolNames;
    synchronized (this) {
      List<ToolSpec> selectedTools = selectTools(n, runId);
      selectedToolNames = toolNames(selectedTools);
      req =
          new ModelRequest(
              endpoint,
              AgentHistory.forModelRequest(messages, 80, 120000),
              selectedTools,
              deepThinking);
    }
    active =
        gateway.stream(
            req,
            new ModelStreamObserver() {
              public void onDelta(String c, String r) {
                if (isActive(runId)) o.onDelta(c, r);
              }

              @Override
              public void onStreamDelta(ModelStreamDelta delta) {
                if (isActive(runId)) o.onStreamDelta(delta);
              }

              public void onError(Throwable e) {
                if (isActive(runId)) {
                  finishRun(runId, "error");
                  o.onError(e);
                }
              }

              public void onComplete(ModelResponse response) {
                if (!isActive(runId)) return;
                synchronized (AgentEngine.this) {
                  messages.add(AgentMessage.assistant(response.content(), response.toolCalls()));
                }
                if (!response.toolCalls().isEmpty()) {
                  executeCalls(
                      demand, o, n, response.toolCalls(), 0, runId, selectedToolNames);
                  return;
                }
                LegacyProtocolParser.Parsed p =
                    new LegacyProtocolParser().parse(response.content());
                if (p != null && !p.finalize) {
                  executeCalls(
                      demand,
                      o,
                      n,
                      Collections.singletonList(new AgentToolCall(p.callId, p.name, p.arguments)),
                      0,
                      runId,
                      selectedToolNames);
                  return;
                }
                if (endpoint.nativeTools() && registry.hasTerminalTool()) {
                  synchronized (AgentEngine.this) {
                    messages.add(AgentMessage.user("请调用已注册的终态工具完成任务，不要只返回普通文本或旧协议终态。"));
                  }
                  round(demand, o, n + 1, runId);
                  return;
                }
                validateAndFinish(demand, o, n, response.content(), "task_completed", runId);
              }
            });
  }

  private List<ToolSpec> selectTools(int round, long runId) {
    AgentRunContext run = activeRunContext;
    if (run == null || run.runId() != runId) {
      run = new AgentRunContext(runId, sessionId, workspaceId, "");
    }
    AgentRoundContext context = new AgentRoundContext(run, round);
    List<ToolSpec> selected = registry.specs();
    for (ToolPolicy policy : toolPolicies) {
      ToolSelection next = policy.selectTools(context, selected);
      selected = retainSelected(selected, next == null ? Collections.emptyList() : next.tools());
    }
    return selected;
  }

  private static List<ToolSpec> retainSelected(
      List<ToolSpec> current, List<ToolSpec> requested) {
    Set<String> names = new LinkedHashSet<>();
    for (ToolSpec tool : requested) if (tool != null) names.add(tool.name());
    List<ToolSpec> retained = new ArrayList<>();
    for (ToolSpec tool : current) if (names.contains(tool.name())) retained.add(tool);
    return Collections.unmodifiableList(retained);
  }

  private static Set<String> toolNames(List<ToolSpec> tools) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    if (tools != null) {
      for (ToolSpec tool : tools) if (tool != null) names.add(tool.name());
    }
    return Collections.unmodifiableSet(names);
  }

  private void executeCalls(
      String demand,
      AgentObserver o,
      int round,
      List<AgentToolCall> calls,
      int index,
      long runId,
      Set<String> selectedToolNames) {
    if (!isActive(runId)) return;
    if (index >= calls.size()) {
      round(demand, o, round + 1, runId);
      return;
    }
    AgentToolCall call = calls.get(index);
    o.onToolStarted(call.id(), call.name(), call.arguments());
    if (registry.find(call.name()) != null
        && (selectedToolNames == null || !selectedToolNames.contains(call.name()))) {
      ToolResult result = ToolResult.error(
          "tool_not_selected", "本轮未提供工具: " + call.name(), true);
      synchronized (this) {
        evidence.add(result);
        messages.add(AgentMessage.tool(call.id(), call.name(), ToolResultCodec.toJson(result)));
      }
      o.onToolCompleted(call.id(), call.name(), result);
      executeCalls(demand, o, round, calls, index + 1, runId, selectedToolNames);
      return;
    }
    active =
        dispatcher.dispatch(
            activeRunContext != null && activeRunContext.runId() == runId
                ? activeRunContext
                : new AgentRunContext(runId, sessionId, workspaceId, demand),
            call.id(),
            call.name(),
            call.arguments(),
            new ToolCallback() {
              public void onProgress(String s, long c, long t, String m) {
                o.onToolProgress(call.id(), s, c, t, m);
              }

              public void onComplete(ToolResult result) {
                if (!isActive(runId)) return;
                synchronized (AgentEngine.this) {
                  evidence.add(result);
                  messages.add(
                      AgentMessage.tool(call.id(), call.name(), ToolResultCodec.toJson(result)));
                }
                o.onToolCompleted(call.id(), call.name(), result);
                if (result.status() == ToolResult.Status.CANCELLED) {
                  finishRun(runId, "cancelled");
                  o.onState("cancelled");
                  return;
                }
                AgentTool executed = registry.find(call.name());
                if (result.isSuccess() && executed != null && executed.requestsFinalize()) {
                  String status = call.arguments().getString("status", "completed");
                  String event =
                      "blocked".equals(status)
                          ? "task_blocked"
                          : "needs_user_input".equals(status)
                              ? "task_needs_user_input"
                              : "task_completed";
                  validateAndFinish(demand, o, round, finalContent(call, result), event, runId);
                  return;
                }
                executeCalls(
                    demand, o, round, calls, index + 1, runId, selectedToolNames);
              }
            });
  }

  private static String finalContent(AgentToolCall call, ToolResult result) {
    String value = call.arguments().getString("summary", call.arguments().getString("content", ""));
    if (value.isEmpty()) {
      Object out = call.arguments().get("final_output");
      value = out == null ? "" : String.valueOf(out);
    }
    return value.isEmpty() ? "任务已完成" : value;
  }

  private void validateAndFinish(
      String demand, AgentObserver o, int round, String content, String terminalEvent, long runId) {
    if (!isActive(runId)) return;
    o.onState("validating");
    ValidationContext c;
    synchronized (this) {
      c = new ValidationContext(sessionId, workspaceId, demand, new ArrayList<>(evidence));
    }
    active =
        validation.run(
            c,
            result -> {
              if (!isActive(runId)) return;
              o.onValidation(result);
              if (result.passed()) {
                hostEvent(terminalEvent, content);
                finishRun(runId, terminalEvent);
                o.onState("completed");
                o.onFinal(content);
              } else {
                hostEvent("validation_failed", feedback(result));
                synchronized (AgentEngine.this) {
                  messages.add(AgentMessage.user(feedback(result)));
                }
                round(demand, o, round + 1, runId);
              }
            });
  }

  private boolean isActive(long runId) {
    return !cancelled.get() && runGeneration.get() == runId;
  }

  private void hostEvent(String type, String message) {
    try {
      definition
          .host()
          .onEvent(
              new WorkbenchEvent(
                  type, message, Collections.singletonMap("workspace_id", workspaceId)));
    } catch (Throwable ignored) {
    }
  }

  private static String feedback(ValidationResult r) {
    StringBuilder b = new StringBuilder("验证未通过，必须修复后重新执行验证：\n");
    for (ValidationIssue i : r.issues())
      if (i.severity() == ValidationIssue.Severity.BLOCKER)
        b.append("- [").append(i.code()).append("] ").append(i.message()).append('\n');
    return b.toString();
  }

  public void cancel() {
    AgentRunContext context = activeRunContext;
    cancelled.set(true);
    runGeneration.incrementAndGet();
    active.cancel();
    if (context != null) finishRun(context.runId(), "cancelled");
  }

  private void finishRun(long runId, String state) {
    if (!finishedRuns.add(runId)) return;
    AgentRunContext context = activeRunContext;
    if (context == null || context.runId() != runId) {
      context = new AgentRunContext(runId, sessionId, workspaceId, "");
    }
    dispatcher.onRunFinished(context, state == null ? "" : state);
  }
}
