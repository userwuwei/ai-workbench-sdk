package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class AgentEngine implements Cancellable {
  public static final String STATE_INTERACTION_LIMIT_PAUSED = "interaction_limit_paused";

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
  private final StableAgentRequestHistory requestHistory = new StableAgentRequestHistory();
  private final Executor executor;
  private final String sessionId, workspaceId;
  private volatile boolean deepThinking;
  private final int maxRounds;
  private final int autoPauseEveryRounds;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicLong runGeneration = new AtomicLong();
  private final Set<Long> finishedRuns = ConcurrentHashMap.newKeySet();
  private volatile Cancellable active = Cancellable.NONE;
  private volatile AgentRunContext activeRunContext;
  private volatile PausedContinuation pausedContinuation;
  private int authorizedRoundLimit;
  private int invalidToolArgumentFailures;

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
    this(
        d,
        g,
        e,
        decisions,
        executor,
        sessionId,
        workspaceId,
        deepThinking,
        maxRounds,
        0);
  }

  public AgentEngine(
      WorkbenchDefinition d,
      ModelGateway g,
      ModelEndpoint e,
      UserDecisionService decisions,
      Executor executor,
      String sessionId,
      String workspaceId,
      boolean deepThinking,
      int maxRounds,
      int autoPauseEveryRounds) {
    definition = d;
    gateway = g;
    endpoint = e;
    this.executor = executor;
    this.sessionId = sessionId;
    this.workspaceId = workspaceId;
    this.deepThinking = deepThinking;
    this.maxRounds = Math.max(1, maxRounds);
    this.autoPauseEveryRounds = Math.max(0, autoPauseEveryRounds);
    this.authorizedRoundLimit = initialAuthorizedRoundLimit();
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
    requestHistory.reset(messages, "");
  }

  public synchronized List<AgentMessage> messages() {
    return Collections.unmodifiableList(new ArrayList<>(messages));
  }

  /** Returns the exact estimated context selected for the latest outbound request. */
  public synchronized RequestContextUsage requestContextUsage() {
    return requestHistory.latestUsage();
  }

  public Cancellable submit(String demand, AgentObserver o) {
    AgentRunContext previousRun = activeRunContext;
    if (previousRun != null) finishRun(previousRun.runId(), "superseded");
    long runId = runGeneration.incrementAndGet();
    cancelled.set(false);
    clearPausedContinuation();
    synchronized (this) {
      authorizedRoundLimit = initialAuthorizedRoundLimit();
      invalidToolArgumentFailures = 0;
    }
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
              requestHistory.reset(messages, demand);
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
    if (pauseBeforeRound(demand, o, n, runId)) return;
    ModelRequest req;
    try {
      synchronized (this) {
        AgentRoundContext roundContext = roundContext(n, runId);
        List<ToolSpec> selectedTools = selectTools(roundContext);
        String requiredToolName = requiredToolName(roundContext, selectedTools);
        StableAgentRequestHistory.Projection projection =
            requestHistory.prepare(messages, selectedTools, demand);
        req =
            new ModelRequest(
                endpoint,
                projection.messages,
                selectedTools,
                deepThinking,
                allowMultipleToolCalls(roundContext),
                requiredToolName);
      }
    } catch (Throwable error) {
      if (isActive(runId)) {
        finishRun(runId, "error");
        o.onError(error);
      }
      return;
    }
    o.onState("model_running");
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
                if (!response.invalidToolCalls().isEmpty()) {
                  handleInvalidToolCall(
                      demand, o, n, response.invalidToolCalls().get(0), runId);
                  return;
                }
                synchronized (AgentEngine.this) {
                  requestHistory.recordUsage(response.usage());
                  messages.add(AgentMessage.assistant(response.content(), response.toolCalls()));
                }
                if (!response.toolCalls().isEmpty()) {
                  executeCalls(demand, o, n, response.toolCalls(), 0, runId);
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
                      runId);
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

  private void handleInvalidToolCall(
      String demand,
      AgentObserver observer,
      int round,
      InvalidToolCall invalid,
      long runId) {
    if (!isActive(runId)) return;
    boolean strict =
        endpoint != null && endpoint.toolArgumentMode() == ToolArgumentMode.STRICT;
    int failureCount;
    synchronized (this) {
      failureCount = ++invalidToolArgumentFailures;
    }
    boolean retryable = !strict && failureCount == 1;
    String detail = invalidToolArgumentDetail(invalid);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("operation", invalid.name());
    data.put("argument_chars", invalid.argumentChars());
    if (invalid.errorOffset() >= 0) data.put("error_offset", invalid.errorOffset());
    if (!invalid.invalidEscape().isEmpty()) {
      data.put("invalid_escape", invalid.invalidEscape());
    }
    data.put("attempt", failureCount);
    ToolResult result =
        ToolResult.error("invalid_tool_arguments", detail, retryable, data);
    observer.onToolStarted(invalid.id(), invalid.name(), ToolArguments.empty());
    observer.onToolCompleted(invalid.id(), invalid.name(), result);
    if (retryable) {
      synchronized (this) {
        messages.add(AgentMessage.user(invalidToolArgumentFeedback(invalid)));
      }
      round(demand, observer, round + 1, runId);
      return;
    }
    finishRun(runId, strict ? "provider_protocol_error" : "invalid_tool_arguments");
    observer.onError(
        new IllegalStateException(
            strict ? "Provider 严格工具协议异常：" + detail : detail));
  }

  private static String invalidToolArgumentDetail(InvalidToolCall invalid) {
    StringBuilder message = new StringBuilder("工具参数 JSON 非法");
    if (!invalid.invalidEscape().isEmpty()) {
      message.append("：非法转义 ").append(invalid.invalidEscape());
    } else if (!invalid.errorMessage().isEmpty()) {
      message.append("：").append(invalid.errorMessage());
    }
    if (invalid.errorOffset() >= 0) {
      message.append("（位置 ").append(invalid.errorOffset()).append("）");
    }
    return message.toString();
  }

  private static String invalidToolArgumentFeedback(InvalidToolCall invalid) {
    return invalidToolArgumentDetail(invalid)
        + "。请重新生成完整工具调用；参数必须是严格 JSON。"
        + "源码反斜杠必须在外层 JSON 中再次转义；禁止在 JSON 层直接使用"
        + " \\UXXXXXXXX 或 \\u{...}，可优先使用原始 Unicode 字符。";
  }

  private AgentRoundContext roundContext(int round, long runId) {
    AgentRunContext run = activeRunContext;
    if (run == null || run.runId() != runId) {
      run = new AgentRunContext(runId, sessionId, workspaceId, "");
    }
    return new AgentRoundContext(run, round);
  }

  private List<ToolSpec> selectTools(AgentRoundContext context) {
    List<ToolSpec> selected = registry.specs();
    for (ToolPolicy policy : toolPolicies) {
      ToolSelection next = policy.selectTools(context, selected);
      selected = retainSelected(selected, next == null ? Collections.emptyList() : next.tools());
    }
    return selected;
  }

  private boolean allowMultipleToolCalls(AgentRoundContext context) {
    for (ToolPolicy policy : toolPolicies) {
      if (policy instanceof MultipleToolCallPolicy
          && ((MultipleToolCallPolicy) policy).allowMultipleToolCalls(context)) return true;
    }
    return false;
  }

  private String requiredToolName(
      AgentRoundContext context, List<ToolSpec> selectedTools) {
    String required = "";
    for (ToolPolicy policy : toolPolicies) {
      if (!(policy instanceof NamedToolChoicePolicy)) continue;
      String candidate = ((NamedToolChoicePolicy) policy).requiredToolName(context);
      candidate = candidate == null ? "" : candidate.trim();
      if (candidate.isEmpty()) continue;
      if (!required.isEmpty() && !required.equals(candidate)) {
        throw new IllegalStateException(
            "conflicting_required_tool_choice: " + required + ", " + candidate);
      }
      required = candidate;
    }
    if (required.isEmpty()) return "";
    for (ToolSpec tool : selectedTools) {
      if (required.equals(tool.name())) return required;
    }
    throw new IllegalStateException("required_tool_not_visible: " + required);
  }

  private static List<ToolSpec> retainSelected(
      List<ToolSpec> current, List<ToolSpec> requested) {
    Set<String> names = new LinkedHashSet<>();
    for (ToolSpec tool : requested) if (tool != null) names.add(tool.name());
    List<ToolSpec> retained = new ArrayList<>();
    for (ToolSpec tool : current) if (names.contains(tool.name())) retained.add(tool);
    return Collections.unmodifiableList(retained);
  }

  private void executeCalls(
      String demand,
      AgentObserver o,
      int round,
      List<AgentToolCall> calls,
      int index,
      long runId) {
    if (!isActive(runId)) return;
    if (index >= calls.size()) {
      round(demand, o, round + 1, runId);
      return;
    }
    AgentToolCall call = calls.get(index);
    o.onToolStarted(call.id(), call.name(), call.arguments());
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
                executeCalls(demand, o, round, calls, index + 1, runId);
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

  private boolean pauseBeforeRound(String demand, AgentObserver observer, int nextRound, long runId) {
    if (autoPauseEveryRounds <= 0) return false;
    synchronized (this) {
      if (!isActive(runId) || nextRound < authorizedRoundLimit) return false;
      pausedContinuation =
          new PausedContinuation(demand, observer, nextRound, runId);
    }
    observer.onState(STATE_INTERACTION_LIMIT_PAUSED);
    return true;
  }

  public boolean resumePausedRun() {
    PausedContinuation continuation;
    synchronized (this) {
      continuation = pausedContinuation;
      if (continuation == null || !isActive(continuation.runId)) return false;
      pausedContinuation = null;
      authorizedRoundLimit = saturatingAdd(authorizedRoundLimit, autoPauseEveryRounds);
    }
    executor.execute(
        () ->
            round(
                continuation.demand,
                continuation.observer,
                continuation.nextRound,
                continuation.runId));
    return true;
  }

  public synchronized boolean isPaused() {
    return pausedContinuation != null && isActive(pausedContinuation.runId);
  }

  private synchronized void clearPausedContinuation() {
    pausedContinuation = null;
  }

  private int initialAuthorizedRoundLimit() {
    return autoPauseEveryRounds <= 0 ? Integer.MAX_VALUE : autoPauseEveryRounds;
  }

  private static int saturatingAdd(int value, int increment) {
    if (increment <= 0 || value >= Integer.MAX_VALUE - increment) return Integer.MAX_VALUE;
    return value + increment;
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
    clearPausedContinuation();
    active.cancel();
    if (context != null) finishRun(context.runId(), "cancelled");
  }

  private void finishRun(long runId, String state) {
    if (!finishedRuns.add(runId)) return;
    synchronized (this) {
      if (pausedContinuation != null && pausedContinuation.runId == runId) {
        pausedContinuation = null;
      }
    }
    AgentRunContext context = activeRunContext;
    if (context == null || context.runId() != runId) {
      context = new AgentRunContext(runId, sessionId, workspaceId, "");
    }
    dispatcher.onRunFinished(context, state == null ? "" : state);
  }

  private static final class PausedContinuation {
    final String demand;
    final AgentObserver observer;
    final int nextRound;
    final long runId;

    PausedContinuation(String demand, AgentObserver observer, int nextRound, long runId) {
      this.demand = demand;
      this.observer = observer;
      this.nextRound = nextRound;
      this.runId = runId;
    }
  }
}
