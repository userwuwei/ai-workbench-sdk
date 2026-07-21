package com.cscjapp.aiworkbench.android;

import android.os.SystemClock;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.SessionSnapshot;
import com.cscjapp.aiworkbench.api.SessionStore;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ValidationIssue;
import com.cscjapp.aiworkbench.api.ValidationResult;
import com.cscjapp.aiworkbench.api.WorkbenchDefinition;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.core.AgentEngine;
import com.cscjapp.aiworkbench.core.AgentMessage;
import com.cscjapp.aiworkbench.core.AgentObserver;
import com.cscjapp.aiworkbench.core.AgentToolCall;
import com.cscjapp.aiworkbench.core.ModelStreamDelta;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.WorkbenchLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lifecycle state plus the complete reference-workbench presentation controller. */
final class WorkbenchViewModel extends ViewModel {
  static final class ToolRunningState {
    final boolean visible;
    final String name;
    final String verb;
    final long startedAtMs;

    ToolRunningState(boolean visible, String name, String verb, long startedAtMs) {
      this.visible = visible;
      this.name = name == null ? "工具执行" : name;
      this.verb = verb == null ? "执行中" : verb;
      this.startedAtMs = startedAtMs;
    }

    static ToolRunningState hidden() {
      return new ToolRunningState(false, "", "", 0L);
    }
  }

  static final class ContextUsageState {
    final long usedTokens;
    final long totalTokens;
    final long messageTokens;
    final long toolsTokens;
    final long remainingTokens;
    final int usedPercent;
    final boolean restored;

    ContextUsageState(long usedTokens, long totalTokens, long messageTokens, long toolsTokens,
        boolean restored) {
      this.usedTokens = Math.max(0L, usedTokens);
      this.totalTokens = Math.max(1L, totalTokens);
      this.messageTokens = Math.max(0L, messageTokens);
      this.toolsTokens = Math.max(0L, toolsTokens);
      this.remainingTokens = Math.max(0L, this.totalTokens - this.usedTokens);
      this.usedPercent =
          Math.max(0, Math.min(100, Math.round(this.usedTokens * 100f / this.totalTokens)));
      this.restored = restored;
    }
  }

  static final class StreamUiUpdate {
    final WorkbenchUiItem thoughtItem;
    final int thoughtMask;
    final WorkbenchUiItem reasonItem;
    final int reasonMask;
    final boolean autoScroll;

    StreamUiUpdate(
        WorkbenchUiItem thoughtItem,
        int thoughtMask,
        WorkbenchUiItem reasonItem,
        int reasonMask,
        boolean autoScroll) {
      this.thoughtItem = thoughtItem;
      this.thoughtMask = thoughtMask;
      this.reasonItem = reasonItem;
      this.reasonMask = reasonMask;
      this.autoScroll = autoScroll;
    }
  }

  private final MutableLiveData<List<WorkbenchUiItem>> items =
      new MutableLiveData<>(new ArrayList<>());
  private final MutableLiveData<List<WorkbenchUiItem>> planItems =
      new MutableLiveData<>(new ArrayList<>());
  private final MutableLiveData<Boolean> running = new MutableLiveData<>(false);
  private final MutableLiveData<Boolean> deepThinking = new MutableLiveData<>(false);
  private final MutableLiveData<ToolRunningState> toolRunning =
      new MutableLiveData<>(ToolRunningState.hidden());
  private final MutableLiveData<ContextUsageState> contextUsage = new MutableLiveData<>();
  private final MutableLiveData<StreamUiUpdate> streamUiUpdates = new MutableLiveData<>();
  final DecisionBroker decisions = new DecisionBroker();

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final List<WorkbenchUiItem> state = new ArrayList<>();
  private final List<WorkbenchUiItem> planState = new ArrayList<>();
  private final List<AgentMessage> restoredMessages = new ArrayList<>();
  private final Map<String, ToolArguments> toolArguments = new LinkedHashMap<>();
  private final Map<String, WorkbenchUiItem> browserItems = new LinkedHashMap<>();
  private final WorkbenchPlanTracker planTracker = new WorkbenchPlanTracker();
  private final List<String> planMetadata = new ArrayList<>();
  private final Set<String> successfulPlanTools = new LinkedHashSet<>();
  private final Map<String, int[]> verificationCounts = new LinkedHashMap<>();
  private final WorkbenchStreamProgressController streamProgress =
      new WorkbenchStreamProgressController();

  private AgentEngine engine;
  private WorkbenchDefinition definition;
  private WorkbenchLaunchRequest request;
  private SessionStore store;
  private ModelEndpoint endpoint;
  private String sessionId;
  private String userName = "你";
  private boolean initialSubmitted;
  private boolean restoredSession;
  private int modelRoundIndex;
  private WorkbenchUiItem currentThought;
  private WorkbenchUiItem currentReason;
  private WorkbenchUiItem currentSummary;
  private WorkbenchUiItem currentPlan;
  private ToolRunningState currentToolRunningState = ToolRunningState.hidden();
  private WorkbenchLogger logger = WorkbenchLogger.none();
  private int taskRunIndex;
  private boolean terminalValidationPassed;

  LiveData<List<WorkbenchUiItem>> items() {
    return items;
  }

  LiveData<List<WorkbenchUiItem>> planItems() {
    return planItems;
  }

  LiveData<Boolean> running() {
    return running;
  }

  LiveData<Boolean> deepThinking() {
    return deepThinking;
  }

  LiveData<ToolRunningState> toolRunning() {
    return toolRunning;
  }

  LiveData<ContextUsageState> contextUsage() {
    return contextUsage;
  }

  LiveData<StreamUiUpdate> streamUiUpdates() {
    return streamUiUpdates;
  }

  synchronized void initialize(
      WorkbenchDefinition definition,
      WorkbenchLaunchRequest request,
      ModelEndpoint endpoint,
      SessionStore store,
      ModelGateway gateway,
      WorkbenchLogger logger) {
    if (engine != null) return;
    if (gateway == null) throw new IllegalArgumentException("model gateway required");
    this.definition = definition;
    this.request = request;
    this.endpoint = endpoint;
    this.store = store;
    this.logger = logger == null ? WorkbenchLogger.none() : logger;
    deepThinking.setValue(request.deepThinking());
    SessionSnapshot previous =
        store == null ? null : store.loadLatest(definition.id(), request.workspaceId());
    restoredSession = previous != null;
    sessionId = previous == null ? UUID.randomUUID().toString() : previous.sessionId();
    restore(previous);
    engine =
        new AgentEngine(
            definition,
            gateway,
            endpoint,
            decisions,
            executor,
            sessionId,
            request.workspaceId(),
            request.deepThinking(),
            40);
    engine.restoreMessages(restoredMessages);
    refreshContextUsage();
  }

  synchronized void setUserName(String value) {
    userName = value == null || value.trim().isEmpty() ? "你" : value.trim();
  }

  synchronized void updateEndpoint(ModelEndpoint endpoint) {
    if (endpoint == null) return;
    this.endpoint = endpoint;
    if (engine != null) engine.updateEndpoint(endpoint);
  }

  synchronized void setDeepThinking(boolean enabled) {
    deepThinking.setValue(enabled);
    if (engine != null) engine.setDeepThinking(enabled);
  }

  synchronized boolean markInitialSubmitted() {
    if (initialSubmitted) return false;
    initialSubmitted = true;
    return true;
  }

  synchronized void submit(String demand) {
    if (Boolean.TRUE.equals(running.getValue()) || demand == null || demand.trim().isEmpty()) {
      return;
    }
    resetCurrentPlanForRun();
    taskRunIndex++;
    state.add(WorkbenchUiItem.userDemand(userName, demand.trim()));
    postItems(false);
    running.setValue(true);
    toolArguments.clear();
    browserItems.clear();
    currentThought = null;
    currentReason = null;
    currentSummary = null;
    modelRoundIndex = 0;
    engine.submit(demand.trim(), observer());
  }

  synchronized boolean tickThinking() {
    if (!Boolean.TRUE.equals(running.getValue())) return false;
    if (currentThought == null) return true;
    String current = currentThought.content == null ? "" : currentThought.content;
    if (!current.startsWith("正在思考中") && !current.startsWith("正在深度思考中")) {
      return false;
    }
    int existingDots = 0;
    for (int i = current.length() - 1; i >= 0 && current.charAt(i) == '.'; i--) existingDots++;
    int nextDots = existingDots >= 3 ? 1 : existingDots + 1;
    StringBuilder animated = new StringBuilder(
        streamProgress.hasReasoning() || Boolean.TRUE.equals(deepThinking.getValue())
            ? "正在深度思考中"
            : "正在思考中");
    for (int i = 0; i < nextDots; i++) animated.append('.');
    currentThought.content = animated.toString();
    postStreamUiUpdate(WorkbenchStreamPayload.THOUGHT, WorkbenchStreamPayload.NONE, false);
    return true;
  }

  synchronized void cancel() {
    if (engine != null) engine.cancel();
    if (Boolean.TRUE.equals(running.getValue())) {
      if (currentReason != null) {
        currentReason.title = roundTitle("已停止");
        replaceReasonContent("本轮已手动终止");
        currentReason.statusLevel = WorkbenchUiItem.STATUS_WARNING;
        currentReason.errorState = false;
        deactivateReasonAura(currentReason);
        currentReason.showProgressCounter = false;
      }
      WorkbenchUiItem summary = WorkbenchUiItem.summary("总结", "本轮已终止");
      summary.statusLevel = WorkbenchUiItem.STATUS_WARNING;
      state.add(summary);
      currentSummary = summary;
      postItems(true);
    }
    hideToolRunning();
    clearReasonStreamingState();
    running.setValue(false);
  }

  synchronized void clear() {
    if (engine != null) engine.cancel();
    state.clear();
    planState.clear();
    restoredMessages.clear();
    toolArguments.clear();
    browserItems.clear();
    planTracker.clear();
    planMetadata.clear();
    successfulPlanTools.clear();
    verificationCounts.clear();
    terminalValidationPassed = false;
    streamProgress.reset();
    currentThought = null;
    currentReason = null;
    currentSummary = null;
    currentPlan = null;
    items.setValue(new ArrayList<>());
    planItems.setValue(new ArrayList<>());
    currentToolRunningState = ToolRunningState.hidden();
    toolRunning.setValue(currentToolRunningState);
    contextUsage.setValue(null);
    streamUiUpdates.setValue(null);
    if (store != null && definition != null && request != null) {
      store.clear(definition.id(), request.workspaceId());
    }
  }

  synchronized void persistUiState() {
    persist();
  }

  private AgentObserver observer() {
    return new AgentObserver() {
      @Override
      public void onState(String value) {
        synchronized (WorkbenchViewModel.this) {
          if ("model_running".equals(value)) {
            beginRound();
            refreshContextUsage();
          } else if ("validating".equals(value)) {
            showToolRunning("验证任务结果", "审核中");
            if (currentReason != null) {
              clearReasonStreamingState();
              currentReason.title = "终态审核";
              replaceReasonContent("正在核对工具证据与完成条件");
              activateReasonAura(currentReason);
              postItems(false);
            }
          } else if ("completed".equals(value) || "cancelled".equals(value)) {
            hideToolRunning();
            if (currentReason != null) {
              clearReasonStreamingState();
              deactivateReasonAura(currentReason);
              postItems(false);
            }
            running.postValue(false);
          }
        }
      }

      @Override
      public void onDelta(String contentDelta, String reasoningDelta) {
        synchronized (WorkbenchViewModel.this) {
          applyStreamDelta(ModelStreamDelta.text(contentDelta, reasoningDelta));
        }
      }

      @Override
      public void onStreamDelta(ModelStreamDelta delta) {
        synchronized (WorkbenchViewModel.this) {
          applyStreamDelta(delta);
        }
      }

      @Override
      public void onToolStarted(String callId, String name, ToolArguments arguments) {
        synchronized (WorkbenchViewModel.this) {
          ensureRound();
          toolArguments.put(callId, arguments == null ? ToolArguments.empty() : arguments);
          markThoughtCompleted();
          currentReason.title = roundTitle("工具结果");
          replaceReasonContent("正在执行 " + displayToolName(name));
          currentReason.detailContent = formatArguments(arguments);
          currentReason.detailExpandable = !currentReason.detailContent.isEmpty();
          clearReasonStreamingState();
          activateReasonAura(currentReason);
          showToolRunning(displayToolName(name), "执行中");
          if ("browser_test".equals(name)) {
            WorkbenchUiItem browser =
                WorkbenchUiItem.browserTest("浏览器测试", "正在执行浏览器自动化", initialBrowserSteps(arguments));
            browser.browserTestStatus = "running";
            browser.browserTestMeta = argument(arguments, "operation", "");
            state.add(browser);
            browserItems.put(callId, browser);
          }
          postItems(false);
        }
      }

      @Override
      public void onToolProgress(
          String callId, String stage, long current, long total, String message) {
        synchronized (WorkbenchViewModel.this) {
          String detail = message == null || message.trim().isEmpty() ? stage : message;
          ToolRunningState active = currentToolRunningState;
          showToolRunning(active == null ? "工具执行" : active.name, "执行中");
          if (currentReason != null) {
            replaceReasonContent(detail);
            clearReasonStreamingState();
            activateReasonAura(currentReason);
            postItems(false);
          }
          WorkbenchUiItem browser = browserItems.get(callId);
          if (browser != null && !detail.isEmpty()) {
            browser.content = detail;
            updateRunningBrowserStep(browser, detail);
            postItems(false);
          }
        }
      }

      @Override
      public void onToolCompleted(String callId, String name, ToolResult result) {
        synchronized (WorkbenchViewModel.this) {
          hideToolRunning();
          clearReasonStreamingState();
          ToolArguments args = toolArguments.get(callId);
          if ("plan_task".equals(name)) {
            renderPlan(args, result);
          } else if ("browser_test".equals(name)) {
            renderBrowserResult(callId, args, result);
          } else if (isWriteTool(name)) {
            renderWriteResult(name, args, result);
          } else {
            renderToolResult(name, args, result);
          }
          if (!"plan_task".equals(name)) recordPlanToolResult(name, result);
          refreshContextUsage();
          postItems(true);
        }
      }

      @Override
      public void onValidation(ValidationResult result) {
        synchronized (WorkbenchViewModel.this) {
          if (currentReason != null) {
            clearReasonStreamingState();
            deactivateReasonAura(currentReason);
          }
          if (result == null || !result.passed()) {
            terminalValidationPassed = false;
            WorkbenchUiItem item = WorkbenchUiItem.summary("终态审核未通过", formatIssues(result));
            item.statusLevel = WorkbenchUiItem.STATUS_WARNING;
            item.detailContent = formatIssues(result);
            item.detailExpandable = true;
            state.add(item);
            currentReason = item;
            logPlanState("validation_failed", "", compactIssues(result));
            postItems(true);
          } else {
            terminalValidationPassed = true;
            if (currentReason != null) {
              currentReason.title = "终态审核通过";
              replaceReasonContent("已核对工具证据与完成条件");
              currentReason.statusLevel = WorkbenchUiItem.STATUS_SUCCESS;
              deactivateReasonAura(currentReason);
              postItems(true);
            }
          }
        }
      }

      @Override
      public void onFinal(String finalContent) {
        synchronized (WorkbenchViewModel.this) {
          hideToolRunning();
          running.postValue(false);
          markThoughtCompleted();
          if (currentReason != null) deactivateReasonAura(currentReason);
          clearReasonStreamingState();
          String value =
              finalContent == null || finalContent.trim().isEmpty() ? "任务已完成" : finalContent.trim();
          currentSummary = WorkbenchUiItem.summary("总结", value);
          currentSummary.statusLevel = WorkbenchUiItem.STATUS_SUCCESS;
          state.add(currentSummary);
          if (terminalValidationPassed) {
            completePlan();
            logPlanState("completed", "", "");
          }
          refreshContextUsage();
          postItems(true);
        }
      }

      @Override
      public void onError(Throwable error) {
        synchronized (WorkbenchViewModel.this) {
          hideToolRunning();
          running.postValue(false);
          markThoughtCompleted();
          String message = error == null || error.getMessage() == null ? "未知错误" : error.getMessage();
          String summaryText = "本轮处理失败：" + message;
          if (currentReason != null) {
            replaceReasonContent(summaryText);
            currentReason.errorState = true;
            currentReason.statusLevel = WorkbenchUiItem.STATUS_ERROR;
            deactivateReasonAura(currentReason);
            clearReasonStreamingState();
          }
          WorkbenchUiItem item = WorkbenchUiItem.summary("总结", summaryText);
          item.errorState = true;
          item.statusLevel = WorkbenchUiItem.STATUS_ERROR;
          state.add(item);
          currentSummary = item;
          postItems(true);
        }
      }
    };
  }

  private void applyStreamDelta(ModelStreamDelta delta) {
    if (delta == null) return;
    ensureRound();
    WorkbenchStreamProgressController.Snapshot snapshot =
        streamProgress.append(delta, endpoint != null && endpoint.nativeTools());
    if (!delta.reasoning().isEmpty()) currentThought.appendStreamCodeBlock(delta.reasoning());
    if (snapshot.kind == WorkbenchStreamProgressController.Kind.INPUT
        && !delta.content().isEmpty()) {
      currentReason.appendStreamContent(delta.content());
    }
    int thoughtMask = WorkbenchStreamPayload.NONE;
    int reasonMask = WorkbenchStreamPayload.NONE;
    if (!snapshot.reasoningText.isEmpty()) {
      String title = roundTitle("深度思考");
      String content = thinkingText();
      if (!same(currentThought.title, title)) {
        currentThought.title = title;
        thoughtMask |= WorkbenchStreamPayload.TITLE;
      }
      if (!same(currentThought.content, content)
          || !same(currentThought.codeBlock, snapshot.reasoningText)
          || !currentThought.codeExpanded) {
        currentThought.content = content;
        currentThought.codeBlock = snapshot.reasoningText;
        currentThought.codeExpanded = true;
        thoughtMask |= WorkbenchStreamPayload.THOUGHT;
      }
    }
    if (snapshot.kind == WorkbenchStreamProgressController.Kind.NONE) {
      if (thoughtMask != WorkbenchStreamPayload.NONE) {
        postStreamUiUpdate(thoughtMask, WorkbenchStreamPayload.NONE, false);
      }
      return;
    }
    if (snapshot.kind == WorkbenchStreamProgressController.Kind.WRITE
        || snapshot.kind == WorkbenchStreamProgressController.Kind.RECEIVE) {
      currentReason.clearStreamedContent();
    }
    if (snapshot.toolCallVisible) {
      String previousThoughtContent = currentThought.content;
      markThoughtCompleted();
      if (!same(previousThoughtContent, currentThought.content)) {
        thoughtMask |= WorkbenchStreamPayload.THOUGHT;
      }
    }
    String reasonTitle = roundTitle(snapshot.titleStage);
    if (!same(currentReason.title, reasonTitle)) {
      currentReason.title = reasonTitle;
      reasonMask |= WorkbenchStreamPayload.TITLE;
    }
    if (!same(currentReason.content, snapshot.content)) {
      currentReason.content = snapshot.content;
      reasonMask |= WorkbenchStreamPayload.CONTENT;
    }
    currentReason.statusLevel = WorkbenchUiItem.STATUS_NORMAL;
    currentReason.errorState = false;
    currentReason.detailContent = "";
    currentReason.detailExpandable = false;
    currentReason.detailExpanded = false;
    if (!currentReason.showProgressCounter
        || currentReason.progressCounterValue != snapshot.value
        || !same(currentReason.progressCounterLabel, snapshot.label)) {
      currentReason.showProgressCounter = true;
      currentReason.progressCounterLabel = snapshot.label;
      currentReason.progressCounterValue = snapshot.value;
      reasonMask |= WorkbenchStreamPayload.COUNTER;
    }
    if (activateReasonAura(currentReason)) reasonMask |= WorkbenchStreamPayload.AURA;
    showToolRunning(snapshot.runningName, snapshot.runningVerb);
    if (thoughtMask != WorkbenchStreamPayload.NONE || reasonMask != WorkbenchStreamPayload.NONE) {
      postStreamUiUpdate(thoughtMask, reasonMask, snapshot.autoScroll);
    }
  }

  private void clearReasonStreamingState() {
    if (currentReason == null) return;
    currentReason.showProgressCounter = false;
    currentReason.progressCounterValue = 0L;
    currentReason.progressCounterLabel = "";
  }

  private void replaceReasonContent(String content) {
    if (currentReason == null) return;
    currentReason.clearStreamedContent();
    currentReason.content = content == null ? "" : content;
  }

  private void postStreamUiUpdate(int thoughtMask, int reasonMask, boolean autoScroll) {
    // LiveData may coalesce background postValue calls. Every retained stream event therefore
    // carries the complete current text/counter payload, while aura remains transition-only.
    if (thoughtMask != WorkbenchStreamPayload.NONE) {
      thoughtMask |= WorkbenchStreamPayload.TITLE | WorkbenchStreamPayload.THOUGHT;
    }
    if (reasonMask != WorkbenchStreamPayload.NONE) {
      reasonMask |=
          WorkbenchStreamPayload.TITLE
              | WorkbenchStreamPayload.CONTENT
              | WorkbenchStreamPayload.COUNTER;
    }
    streamUiUpdates.postValue(
        new StreamUiUpdate(
            thoughtMask == WorkbenchStreamPayload.NONE ? null : currentThought,
            thoughtMask,
            reasonMask == WorkbenchStreamPayload.NONE ? null : currentReason,
            reasonMask,
            autoScroll));
  }

  private void beginRound() {
    if (currentReason != null) {
      clearReasonStreamingState();
      deactivateReasonAura(currentReason);
    }
    modelRoundIndex++;
    streamProgress.reset();
    currentThought = WorkbenchUiItem.thought(roundTitle("思考过程"), thinkingText(), "");
    currentReason = WorkbenchUiItem.reason(roundTitle("模型输出"), "等待模型选择下一步动作");
    activateReasonAura(currentReason);
    state.add(currentThought);
    state.add(currentReason);
    showToolRunning("模型响应中", "处理中");
    postItems(false);
  }

  private void ensureRound() {
    if (currentThought == null || currentReason == null) beginRound();
  }

  private String thinkingText() {
    return streamProgress.hasReasoning() || Boolean.TRUE.equals(deepThinking.getValue())
        ? "正在深度思考中..."
        : "正在思考中...";
  }

  private String roundTitle(String stage) {
    return modelRoundIndex <= 0 ? stage : "第 " + modelRoundIndex + " 轮 · " + stage;
  }

  private void markThoughtCompleted() {
    if (currentThought == null) return;
    String value = currentThought.content == null ? "" : currentThought.content.trim();
    if (value.isEmpty() || value.startsWith("正在思考") || value.startsWith("正在深度思考")) {
      currentThought.content = "本轮思考完成";
    }
  }

  private void showToolRunning(String name, String verb) {
    ToolRunningState existing = currentToolRunningState;
    String resolvedName = name == null ? "工具执行" : name;
    String resolvedVerb = verb == null ? "执行中" : verb;
    boolean same = existing != null && existing.visible
        && existing.name.equals(resolvedName) && existing.verb.equals(resolvedVerb);
    if (same) return;
    currentToolRunningState =
        new ToolRunningState(true, resolvedName, resolvedVerb, SystemClock.elapsedRealtime());
    toolRunning.postValue(currentToolRunningState);
  }

  private void hideToolRunning() {
    ToolRunningState existing = currentToolRunningState;
    if (existing == null || !existing.visible) return;
    currentToolRunningState = ToolRunningState.hidden();
    toolRunning.postValue(currentToolRunningState);
  }

  private boolean activateReasonAura(WorkbenchUiItem item) {
    if (item == null) return false;
    for (WorkbenchUiItem candidate : state) {
      if (candidate == item) continue;
      if (WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA.equals(candidate.waitingEffect)) {
        candidate.waitingEffect = WorkbenchUiItem.WAITING_EFFECT_NONE;
        candidate.waitingEffectStartedAtMs = 0L;
      }
    }
    if (WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA.equals(item.waitingEffect)
        && item.waitingEffectStartedAtMs > 0L) {
      return false;
    }
    item.waitingEffect = WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA;
    item.waitingEffectStartedAtMs = SystemClock.uptimeMillis();
    return true;
  }

  private static boolean deactivateReasonAura(WorkbenchUiItem item) {
    if (item == null) return false;
    boolean changed =
        WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA.equals(item.waitingEffect)
            || item.waitingEffectStartedAtMs != 0L;
    item.waitingEffect = WorkbenchUiItem.WAITING_EFFECT_NONE;
    item.waitingEffectStartedAtMs = 0L;
    return changed;
  }

  private static boolean same(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private void renderPlan(ToolArguments args, ToolResult result) {
    if (result == null || !result.isSuccess()) {
      renderToolResult("plan_task", args, result);
      return;
    }
    Map<String, Object> source =
        result.data() == null || result.data().isEmpty()
            ? (args == null ? Collections.emptyMap() : args.asMap())
            : result.data();
    planTracker.clear();
    planMetadata.clear();
    String goal = value(source.get("goal"));
    if (!goal.isEmpty()) planMetadata.add("目标：" + goal);
    String files = summarizeList(source.get("planned_files"), 5, 160);
    if (!files.isEmpty()) planMetadata.add("涉及文件：" + files);
    String verification = summarizeList(source.get("verification_plan"), 5, 160);
    if (!verification.isEmpty()) planMetadata.add("验证策略：" + verification);
    String qualityMode = value(source.get("quality_mode"));
    boolean interfaceProduct = "interface_product".equals(qualityMode);
    planMetadata.add(interfaceProduct ? "质量模式：界面产品化" : "质量模式：标准代码质量");
    String quality = summarizeMapValues(source.get("quality_bar"), 2, 96);
    if (!quality.isEmpty()) planMetadata.add("质量标准：" + quality);
    String verificationHistory = verificationSummary();
    if (!verificationHistory.isEmpty()) planMetadata.add("验证记录：" + verificationHistory);
    planMetadata.add("质检状态：未审查");
    if (interfaceProduct) {
      planMetadata.add(source.get("interface_design_spec") instanceof Map
          ? "设计规格：已定义" : "设计规格：未定义");
      planMetadata.add("打磨状态：未声明");
    }
    planTracker.load(source.get("steps"));
    if (planTracker.isEmpty()) planTracker.load(defaultPlanSteps(source, interfaceProduct));
    for (String tool : successfulPlanTools) planTracker.recordTool(tool);
    rebuildPlanItem();
    logPlanState("created", "", "");
    if (currentReason != null) {
      currentReason.title = "任务计划已建立";
      replaceReasonContent(goal.isEmpty() ? "已建立任务执行计划" : goal);
      currentReason.statusLevel = WorkbenchUiItem.STATUS_SUCCESS;
      deactivateReasonAura(currentReason);
    }
  }

  private void rebuildPlanItem() {
    List<String> lines = new ArrayList<>(planMetadata);
    lines.addAll(planTracker.displayLines());
    String current = planTracker.currentTitle();
    if (current.isEmpty()) current = "等待继续";
    if (currentPlan == null) {
      currentPlan = WorkbenchUiItem.plan("任务计划 · 当前：" + current, lines);
      planState.clear();
      planState.add(currentPlan);
    } else {
      currentPlan.title = "任务计划 · 当前：" + current;
      currentPlan.steps = lines;
    }
    planItems.postValue(new ArrayList<>(planState));
  }

  private void recordPlanToolResult(String toolName, ToolResult result) {
    boolean passed = semanticToolPassed(toolName, result);
    if (isVerificationTool(toolName)) {
      int[] counts = verificationCounts.get(toolName);
      if (counts == null) {
        counts = new int[2];
        verificationCounts.put(toolName, counts);
      }
      counts[passed ? 0 : 1]++;
      if (!planTracker.isEmpty()) {
        replacePlanMetadata("验证记录：", "验证记录：" + verificationSummary());
      }
    }
    Map<String, Object> data =
        result == null || result.data() == null ? Collections.emptyMap() : result.data();
    if ("quality_review".equals(toolName)) applyQualityReview(data, passed);
    if (!passed) {
      if (isVerificationTool(toolName) || "quality_review".equals(toolName)) {
        logPlanState("verification_failed", toolName, resultMessage(result, "未通过"));
      }
      return;
    }
    successfulPlanTools.add(toolName);
    if (planTracker.recordTool(toolName)) {
      rebuildPlanItem();
      logPlanState("advanced", toolName, "");
    }
  }

  static boolean semanticToolPassed(String toolName, ToolResult result) {
    if (result == null || !result.isSuccess()) return false;
    Map<String, Object> data = result.data();
    String status = data == null ? "" : value(data.get("status"));
    if ("error".equals(status) || "failed".equals(status) || "blocked".equals(status)) return false;
    if (data != null && data.containsKey("passed") && !bool(data.get("passed"), false)) return false;
    Map<?, ?> audit = data == null ? Collections.emptyMap() : map(data.get("layout_audit"));
    if (audit.containsKey("passed") && !bool(audit.get("passed"), false)) return false;
    if ("quality_review".equals(toolName)) {
      return data != null
          && bool(data.get("passed"), false)
          && !bool(data.get("minimal_version_risk"), false)
          && isEmptyCollection(data.get("blocking_gaps"))
          && isEmptyCollection(data.get("claimed_but_unsupported"));
    }
    return true;
  }

  private static boolean isVerificationTool(String toolName) {
    return toolName != null
        && (toolName.endsWith("_test")
            || toolName.endsWith("_check")
            || toolName.startsWith("verify"));
  }

  private void applyQualityReview(Map<String, Object> review, boolean passed) {
    replacePlanMetadata("质检状态：", passed ? "质检状态：已通过" : "质检状态：有待补强");

    String polish = value(review.get("experience_polish_status"));
    if ("separate_pass_done".equals(polish)) {
      replacePlanMetadata("打磨状态：", "打磨状态：已独立完成");
    } else if ("integrated_in_implementation".equals(polish)) {
      replacePlanMetadata("打磨状态：", "打磨状态：并入实现");
    } else {
      replacePlanMetadata("打磨状态：", "打磨状态：未声明");
    }

    if (passed) {
      replacePlanMetadata("待补强：", null);
    } else {
      String gaps = summarizeList(review.get("blocking_gaps"), 3, 120);
      replacePlanMetadata("待补强：", gaps.isEmpty() ? null : "待补强：" + gaps);
    }
    rebuildPlanItem();
  }

  private void replacePlanMetadata(String prefix, String replacement) {
    for (int i = planMetadata.size() - 1; i >= 0; i--) {
      if (planMetadata.get(i).startsWith(prefix)) planMetadata.remove(i);
    }
    if (replacement != null && !replacement.isEmpty()) planMetadata.add(replacement);
  }

  private void completePlan() {
    if (planTracker.isEmpty()) return;
    planTracker.complete();
    rebuildPlanItem();
  }

  private void resetCurrentPlanForRun() {
    planState.clear();
    planTracker.clear();
    planMetadata.clear();
    successfulPlanTools.clear();
    verificationCounts.clear();
    terminalValidationPassed = false;
    currentPlan = null;
    planItems.setValue(new ArrayList<>());
  }

  private static List<Map<String, Object>> defaultPlanSteps(
      Map<String, Object> source, boolean interfaceProduct) {
    List<Map<String, Object>> steps = new ArrayList<>();
    steps.add(
        planStep(
            "implement",
            "完成核心实现",
            "implement",
            Collections.<String>emptyList()));
    String verification = summarizeList(source.get("verification_plan"), 3, 96);
    steps.add(
        planStep(
            "verify",
            verification.isEmpty() ? "执行真实验证" : "执行验证：" + verification,
            "verify",
            verificationToolNames(source.get("verification_plan"))));
    if (interfaceProduct || bool(source.get("self_review_required"), false)) {
      steps.add(
          planStep(
              "quality",
              "提交结构化质量审查",
              "quality",
              Collections.singletonList("quality_review")));
    }
    steps.add(
        planStep(
            "finalize",
            "核对证据并结束任务",
            "finalize",
            Collections.singletonList("finalize_task")));
    return steps;
  }

  private static Map<String, Object> planStep(
      String id, String title, String phase, List<String> requiredTools) {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("id", id);
    step.put("title", title);
    step.put("phase", phase);
    step.put("required_tools", requiredTools);
    step.put("status", "pending");
    return step;
  }

  private void logPlanState(String event, String tool, String detail) {
    if (planTracker.isEmpty()) return;
    logger.log(
        "plan_state",
        formatPlanLog(
            Math.max(1, taskRunIndex),
            event,
            metadataValue("目标："),
            metadataValue("涉及文件："),
            planTracker.labels(),
            planTracker.currentPhase(),
            tool,
            verificationSummary(),
            detail));
  }

  static String formatPlanLog(
      int run,
      String event,
      String goal,
      String files,
      List<String> steps,
      String current,
      String tool,
      String verification,
      String detail) {
    StringBuilder message = new StringBuilder();
    message
        .append("[任务计划][run=")
        .append(Math.max(1, run))
        .append("][event=")
        .append(event)
        .append("]\n");
    appendLogLine(message, "目标", goal);
    appendLogLine(message, "文件", files);
    appendLogLine(message, "步骤", joinLimited(steps, " → ", 260));
    appendLogLine(message, "当前", current);
    if (tool != null && !tool.isEmpty()) appendLogLine(message, "工具", tool);
    if (!verification.isEmpty()) appendLogLine(message, "验证", verification);
    if (detail != null && !detail.isEmpty()) {
      appendLogLine(message, "说明", compactLogValue(detail, 180));
    }
    return compactLogValue(message.toString().trim(), 1000);
  }

  private String metadataValue(String prefix) {
    for (String value : planMetadata) {
      if (value.startsWith(prefix)) return value.substring(prefix.length()).trim();
    }
    return "";
  }

  private String verificationSummary() {
    List<String> values = new ArrayList<>();
    for (Map.Entry<String, int[]> entry : verificationCounts.entrySet()) {
      int[] counts = entry.getValue();
      StringBuilder value =
          new StringBuilder(entry.getKey()).append(' ').append(counts[0]).append("通过");
      if (counts[1] > 0) value.append('/').append(counts[1]).append("失败");
      values.add(value.toString());
    }
    return joinLimited(values, "；", 220);
  }

  private static void appendLogLine(StringBuilder out, String key, String value) {
    if (value == null || value.isEmpty()) return;
    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    out.append(key).append('=').append(value);
  }

  private static String compactLogValue(String value, int maxChars) {
    if (value == null) return "";
    String compact = value.replace('\r', ' ').replace("\n\n", "\n").trim();
    return compact.length() <= maxChars ? compact : compact.substring(0, maxChars - 1) + "…";
  }

  private static String compactIssues(ValidationResult result) {
    if (result == null) return "validation_failed";
    List<String> values = new ArrayList<>();
    for (ValidationIssue issue : result.issues()) {
      if (issue == null) continue;
      values.add(issue.code() == null || issue.code().isEmpty() ? issue.message() : issue.code());
      if (values.size() >= 3) break;
    }
    return joinLimited(values, "；", 180);
  }

  private static List<String> strings(Object raw) {
    List<String> result = new ArrayList<>();
    if (raw instanceof List) {
      for (Object value : (List<?>) raw) {
        String text = value(value);
        if (!text.isEmpty()) result.add(text);
      }
    } else {
      String text = value(raw);
      if (!text.isEmpty()) result.add(text);
    }
    return result;
  }

  private static List<String> verificationToolNames(Object raw) {
    List<String> result = new ArrayList<>();
    for (String value : strings(raw)) {
      String[] tokens = value.split("[^A-Za-z0-9_]+");
      for (String token : tokens) {
        if (token.matches("[A-Za-z][A-Za-z0-9_]*")
            && (token.endsWith("_test")
                || token.endsWith("_check")
                || token.startsWith("verify"))) {
          if (!result.contains(token)) result.add(token);
        }
      }
    }
    return result;
  }

  private void renderBrowserResult(
      String callId, ToolArguments args, ToolResult result) {
    WorkbenchUiItem item = browserItems.get(callId);
    if (item == null) {
      item = WorkbenchUiItem.browserTest("浏览器测试", "", initialBrowserSteps(args));
      state.add(item);
    }
    boolean success = result != null && result.isSuccess();
    Map<String, Object> data = result == null ? Collections.emptyMap() : result.data();
    boolean passed = bool(data.get("passed"), success);
    Map<?, ?> layoutAudit = map(data.get("layout_audit"));
    if (!layoutAudit.isEmpty() && layoutAudit.containsKey("passed")) {
      passed = passed && bool(layoutAudit.get("passed"), true);
    }
    item.browserTestStatus = success && passed ? "success" : "error";
    item.content = success && passed ? "浏览器测试通过"
        : first(data, "failure_reason", "message");
    if (item.content.isEmpty()) item.content = resultMessage(result, "浏览器测试未通过");
    item.browserTestMeta = first(data, "final_url", "opened_url", "url", "target_url", "entry_path", "screenshot_path");
    String screenshot = first(layoutAudit, "screenshot_path");
    if (!screenshot.isEmpty()) {
      item.browserTestMeta = item.browserTestMeta.isEmpty() ? screenshot
          : item.browserTestMeta + "\n截图：" + screenshot;
    }
    String blockers = formatValue(layoutAudit.get("blocking_issues"), 0);
    if (!blockers.isEmpty()) {
      item.content = item.content + "\n阻塞问题：" + blockers;
    }
    List<String> resultSteps = browserSteps(data.get("steps"));
    if (!resultSteps.isEmpty()) item.steps = resultSteps;
    else finishBrowserSteps(item, success && passed);
    if (currentReason != null) {
      currentReason.title = success && passed ? "浏览器测试通过" : "浏览器测试未通过";
      replaceReasonContent(item.content);
      currentReason.statusLevel = success && passed ? WorkbenchUiItem.STATUS_SUCCESS : WorkbenchUiItem.STATUS_ERROR;
      currentReason.errorState = !success || !passed;
      deactivateReasonAura(currentReason);
    }
  }

  private void renderWriteResult(String name, ToolArguments args, ToolResult result) {
    boolean success = result != null && result.isSuccess();
    String path = resolvePath(args, result);
    WorkbenchUiItem item = WorkbenchUiItem.editNotice(success ? "已修改文件" : "文件修改失败", "");
    item.content = WorkbenchToolNoticeFormatter.build(name, args, result, request.workspaceId());
    item.statusLevel = success ? WorkbenchUiItem.STATUS_SUCCESS : WorkbenchUiItem.STATUS_ERROR;
    item.errorState = !success;
    if (success && "search_replace".equals(name) && args != null) {
      String oldText = args.getString("old", "");
      String newText = args.getString("new", "");
      int replacementCount = 0;
      Object replacements = args.get("replacements");
      if (replacements instanceof List) {
        StringBuilder batchDiff = new StringBuilder();
        for (Object raw : (List<?>) replacements) {
          Map<?, ?> replacement = map(raw);
          String oldValue = first(replacement, "old");
          String newValue = first(replacement, "new");
          if (oldValue.isEmpty() && newValue.isEmpty()) continue;
          if (batchDiff.length() > 0) batchDiff.append('\n');
          batchDiff.append(diff(oldValue, newValue));
          replacementCount++;
        }
        if (batchDiff.length() > 0) {
          oldText = "";
          newText = "";
          item.diffVisible = true;
          item.diffTitle = path.isEmpty() ? "代码变更" : new File(path).getName();
          item.diffMeta = replacementCount + " 处精确替换";
          item.diffText = batchDiff.toString().trim();
        }
      }
      if (!oldText.isEmpty() || !newText.isEmpty()) {
        item.diffVisible = true;
        item.diffTitle = path.isEmpty() ? "代码变更" : new File(path).getName();
        item.diffMeta = "精确替换";
        item.diffText = diff(oldText, newText);
      }
    }
    state.add(item);
    if (currentReason != null) {
      replaceReasonContent(success ? writeClosure(item.content) : item.content);
      currentReason.statusLevel = item.statusLevel;
      currentReason.errorState = item.errorState;
      deactivateReasonAura(currentReason);
    }
  }

  private void renderToolResult(String name, ToolArguments args, ToolResult result) {
    ensureRound();
    boolean success = result != null && result.isSuccess();
    currentReason.title = success ? displayToolName(name) + "完成" : displayToolName(name) + "失败";
    replaceReasonContent(WorkbenchToolNoticeFormatter.build(name, args, result, request.workspaceId()));
    currentReason.statusLevel = success ? WorkbenchUiItem.STATUS_SUCCESS : WorkbenchUiItem.STATUS_ERROR;
    currentReason.errorState = !success;
    deactivateReasonAura(currentReason);
    currentReason.showProgressCounter = false;
    String detail = success ? formatMap(result.data()) : formatArguments(args);
    currentReason.detailContent = detail;
    currentReason.detailExpandable = !detail.isEmpty();
  }

  private void refreshContextUsage() {
    if (engine == null) return;
    StringBuilder messagesText = new StringBuilder();
    for (AgentMessage message : engine.messages()) {
      messagesText.append(message.role()).append(':').append(message.content()).append('\n');
      for (AgentToolCall call : message.toolCalls()) {
        messagesText.append(call.name()).append(':').append(call.arguments().asMap()).append('\n');
      }
    }
    StringBuilder toolsText = new StringBuilder();
    if (definition != null && definition.tools() != null) {
      definition.tools().forEach(tool -> {
        if (tool == null || tool.spec() == null) return;
        toolsText.append(tool.spec().name()).append(':')
            .append(tool.spec().description()).append(':')
            .append(tool.spec().inputSchema()).append('\n');
      });
    }
    long messageTokens = estimateTokens(messagesText);
    long toolsTokens = estimateTokens(toolsText);
    contextUsage.postValue(new ContextUsageState(
        messageTokens + toolsTokens, 258_000L, messageTokens, toolsTokens, restoredSession));
  }

  private static long estimateTokens(CharSequence text) {
    if (text == null || text.length() == 0) return 0L;
    double tokens = 0d;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c <= 0x007F) tokens += Character.isWhitespace(c) ? 0.15d : 0.28d;
      else if (isCjk(c)) tokens += 1.0d;
      else tokens += 0.65d;
    }
    return (long) Math.ceil(tokens);
  }

  private static boolean isCjk(char c) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
        || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
  }

  private void postItems(boolean persist) {
    items.postValue(new ArrayList<>(state));
    if (persist) persist();
  }

  private void persist() {
    if (store == null || definition == null || request == null) return;
    List<Map<String, Object>> uiItems = new ArrayList<>();
    for (WorkbenchUiItem item : state) uiItems.add(item.toMap());
    List<Map<String, Object>> uiPlans = new ArrayList<>();
    for (WorkbenchUiItem item : planState) uiPlans.add(item.toMap());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", uiItems);
    data.put("plan_items", uiPlans);
    data.put("plan_steps", planTracker.snapshot());
    data.put("plan_step_labels", planTracker.labels());
    data.put("plan_step_states", planTracker.states());
    data.put("plan_metadata", new ArrayList<>(planMetadata));
    data.put("task_run_index", taskRunIndex);
    data.put("model_round", modelRoundIndex);
    data.put("deep_thinking", Boolean.TRUE.equals(deepThinking.getValue()));
    if (engine != null) data.put("messages", encodeMessages(engine.messages()));
    store.save(new SessionSnapshot(4, sessionId, definition.id(), request.workspaceId(), data));
  }

  private void restore(SessionSnapshot snapshot) {
    if (snapshot == null) return;
    restoreItems(snapshot.state().get("items"), state);
    restoreItems(snapshot.state().get("plan_items"), planState);
    if (snapshot.schemaVersion() >= 4 && snapshot.state().get("plan_steps") instanceof List) {
      planTracker.restore(snapshot.state().get("plan_steps"));
    } else {
      planTracker.restoreLegacy(
          snapshot.state().get("plan_step_labels"), snapshot.state().get("plan_step_states"));
    }
    restoreStrings(snapshot.state().get("plan_metadata"), planMetadata);
    modelRoundIndex = integer(snapshot.state().get("model_round"), 0);
    taskRunIndex = integer(snapshot.state().get("task_run_index"), 0);
    terminalValidationPassed = false;
    if (!planState.isEmpty()) {
      currentPlan = planState.get(0);
      currentPlan.title = "任务计划 · 历史计划";
      List<String> lines = new ArrayList<>(planMetadata);
      lines.addAll(planTracker.displayLines());
      currentPlan.steps = lines;
    }
    Object rawMessages = snapshot.state().get("messages");
    if (rawMessages instanceof List) decodeMessages((List<?>) rawMessages);
    items.setValue(new ArrayList<>(state));
    planItems.setValue(new ArrayList<>(planState));
  }

  private static void restoreItems(Object raw, List<WorkbenchUiItem> target) {
    if (!(raw instanceof List)) return;
    for (Object value : (List<?>) raw) {
      WorkbenchUiItem item = WorkbenchUiItem.from(value);
      if (item != null) target.add(item);
    }
  }

  private static void restoreStrings(Object raw, List<String> target) {
    if (!(raw instanceof List)) return;
    for (Object value : (List<?>) raw) if (value != null) target.add(String.valueOf(value));
  }

  private List<Map<String, Object>> encodeMessages(List<AgentMessage> source) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (AgentMessage message : source) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("role", message.role().name());
      value.put("content", message.content());
      value.put("name", message.name());
      value.put("tool_call_id", message.toolCallId());
      List<Map<String, Object>> calls = new ArrayList<>();
      for (AgentToolCall call : message.toolCalls()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", call.id());
        item.put("name", call.name());
        item.put("args", call.arguments().asMap());
        calls.add(item);
      }
      value.put("tool_calls", calls);
      result.add(value);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private void decodeMessages(List<?> source) {
    for (Object raw : source) {
      if (!(raw instanceof Map)) continue;
      Map<?, ?> value = (Map<?, ?>) raw;
      String role = String.valueOf(value.get("role"));
      String content = value.get("content") == null ? "" : String.valueOf(value.get("content"));
      if ("SYSTEM".equals(role)) restoredMessages.add(AgentMessage.system(content));
      else if ("USER".equals(role)) restoredMessages.add(AgentMessage.user(content));
      else if ("TOOL".equals(role))
        restoredMessages.add(
            AgentMessage.tool(
                String.valueOf(value.get("tool_call_id")),
                String.valueOf(value.get("name")),
                content));
      else {
        List<AgentToolCall> calls = new ArrayList<>();
        Object rawCalls = value.get("tool_calls");
        if (rawCalls instanceof List) {
          for (Object rawCall : (List<?>) rawCalls) {
            if (!(rawCall instanceof Map)) continue;
            Map<?, ?> call = (Map<?, ?>) rawCall;
            Object args = call.get("args");
            calls.add(
                new AgentToolCall(
                    String.valueOf(call.get("id")),
                    String.valueOf(call.get("name")),
                    new ToolArguments(
                        args instanceof Map ? (Map<String, ?>) args : Collections.emptyMap())));
          }
        }
        restoredMessages.add(AgentMessage.assistant(content, calls));
      }
    }
  }

  private static List<String> initialBrowserSteps(ToolArguments args) {
    if (args == null) return new ArrayList<>();
    Object raw = args.get("steps");
    List<String> result = new ArrayList<>();
    if (raw instanceof List) {
      for (Object value : (List<?>) raw) {
        if (value instanceof Map) {
          String action = first((Map<?, ?>) value, "action", "label");
          if (!action.isEmpty()) result.add("pending::" + action);
        } else if (value != null) result.add("pending::" + value);
      }
    }
    if (result.isEmpty()) result.add("running::加载页面并执行布局审计");
    return result;
  }

  private static List<String> browserSteps(Object raw) {
    List<String> result = new ArrayList<>();
    if (!(raw instanceof List)) return result;
    for (Object value : (List<?>) raw) {
      if (value instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) value;
        String status = first(map, "status", "state");
        String label = first(map, "label", "action", "message");
        if (!label.isEmpty()) result.add(normalizeBrowserStatus(status) + "::" + label);
      } else if (value != null) result.add("success::" + value);
    }
    return result;
  }

  private static void updateRunningBrowserStep(WorkbenchUiItem item, String detail) {
    if (item.steps == null || item.steps.isEmpty()) {
      item.steps = new ArrayList<>();
      item.steps.add("running::" + detail);
      return;
    }
    for (int i = 0; i < item.steps.size(); i++) {
      if (item.steps.get(i).startsWith("running::")) {
        item.steps.set(i, "success::" + item.steps.get(i).substring("running::".length()));
        if (i + 1 < item.steps.size()) {
          String next = item.steps.get(i + 1);
          int split = next.indexOf("::");
          item.steps.set(i + 1, "running::" + (split >= 0 ? next.substring(split + 2) : next));
        }
        return;
      }
    }
  }

  private static void finishBrowserSteps(WorkbenchUiItem item, boolean success) {
    if (item.steps == null) return;
    for (int i = 0; i < item.steps.size(); i++) {
      String value = item.steps.get(i);
      int split = value.indexOf("::");
      String label = split >= 0 ? value.substring(split + 2) : value;
      item.steps.set(i, (success ? "success" : i == item.steps.size() - 1 ? "error" : "success") + "::" + label);
    }
  }

  private static String normalizeBrowserStatus(String value) {
    if ("success".equals(value) || "passed".equals(value) || "done".equals(value)) return "success";
    if ("error".equals(value) || "failed".equals(value) || "blocked".equals(value)) return "error";
    if ("running".equals(value) || "in_progress".equals(value)) return "running";
    return "pending";
  }

  private static boolean isWriteTool(String name) {
    return "create_file".equals(name) || "search_replace".equals(name) || "rewrite".equals(name);
  }

  private static boolean isReadOnlyTool(String name) {
    return "list_dir".equals(name) || "read_file".equals(name) || "read_file_batch".equals(name) || "read_plan".equals(name);
  }

  private static String resolvePath(ToolArguments args, ToolResult result) {
    if (result != null) {
      String path = first(result.data(), "resolved_path", "path", "requested_path");
      if (!path.isEmpty()) return path;
    }
    return args == null ? "" : args.getString("path", args.getString("entry_path", ""));
  }

  private static String diff(String oldText, String newText) {
    StringBuilder value = new StringBuilder();
    for (String line : oldText.split("\\r?\\n", -1)) if (!line.isEmpty()) value.append('-').append(line).append('\n');
    for (String line : newText.split("\\r?\\n", -1)) if (!line.isEmpty()) value.append('+').append(line).append('\n');
    return value.toString().trim();
  }

  private static String writeClosure(String notice) {
    if (notice == null || notice.trim().isEmpty()) return "已写入工具结果";
    for (String line : notice.split("\\r?\\n")) {
      String value = line.trim();
      if (value.startsWith("编辑文件：")) return "已写入 " + value.substring("编辑文件：".length()).trim();
      if (value.startsWith("文件：")) return "已写入 " + value.substring("文件：".length()).trim();
    }
    int line = notice.indexOf('\n');
    return (line < 0 ? notice : notice.substring(0, line)).trim();
  }

  private static String displayToolName(String name) {
    if ("list_dir".equals(name)) return "查看目录";
    if ("read_file".equals(name) || "read_file_batch".equals(name)) return "读取文件";
    if ("create_file".equals(name)) return "创建文件";
    if ("search_replace".equals(name)) return "修改文件";
    if ("rewrite".equals(name)) return "写入代码结构";
    if ("syntax_check".equals(name)) return "语法检查";
    if ("browser_test".equals(name)) return "浏览器测试";
    if ("quality_review".equals(name)) return "质量自查";
    if ("finalize_task".equals(name)) return "完成任务";
    if ("plan_task".equals(name)) return "任务规划";
    return name == null || name.isEmpty() ? "工具执行" : name;
  }

  private static String summarizeToolData(Map<String, Object> data) {
    if (data == null || data.isEmpty()) return "执行完成";
    String message = first(data, "summary", "message", "result", "status", "path", "resolved_path");
    return message.isEmpty() ? "执行完成" : message;
  }

  private static String resultMessage(ToolResult result, String fallback) {
    if (result == null) return fallback;
    if (result.status() == ToolResult.Status.CANCELLED) return "已取消";
    return result.message() == null || result.message().trim().isEmpty() ? fallback : result.message();
  }

  private static String formatArguments(ToolArguments args) {
    return args == null ? "" : formatMap(args.asMap());
  }

  private static String formatMap(Map<?, ?> map) {
    if (map == null || map.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (out.length() > 0) out.append('\n');
      out.append(entry.getKey()).append("：").append(formatValue(entry.getValue(), 0));
    }
    return out.toString();
  }

  private static String formatValue(Object raw, int depth) {
    if (raw == null) return "";
    if (raw instanceof Map) return compactMap(raw);
    if (raw instanceof List) {
      StringBuilder out = new StringBuilder();
      int count = 0;
      for (Object value : (List<?>) raw) {
        if (count++ > 0) out.append("；");
        out.append(formatValue(value, depth + 1));
        if (count >= 8) break;
      }
      return out.toString();
    }
    String value = String.valueOf(raw);
    return value.length() > 1200 ? value.substring(0, 1200) + "…" : value;
  }

  private static String compactMap(Object raw) {
    if (!(raw instanceof Map)) return value(raw);
    StringBuilder out = new StringBuilder();
    int count = 0;
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
      String value = formatValue(entry.getValue(), 1);
      if (value.isEmpty()) continue;
      if (count++ > 0) out.append(" / ");
      out.append(entry.getKey()).append("：").append(value);
      if (count >= 4) break;
    }
    return out.toString();
  }

  private static String summarizeMapValues(Object raw, int limit, int maxChars) {
    if (!(raw instanceof Map)) return "";
    List<String> values = new ArrayList<>();
    for (Object item : ((Map<?, ?>) raw).values()) {
      String summary = summarizeValue(item);
      if (!summary.isEmpty()) values.add(summary);
      if (values.size() >= limit) break;
    }
    return joinLimited(values, "；", maxChars);
  }

  private static String summarizeValue(Object raw) {
    if (raw instanceof List) {
      for (Object item : (List<?>) raw) {
        String summary = summarizeValue(item);
        if (!summary.isEmpty()) return summary;
      }
      return "";
    }
    if (raw instanceof Map) {
      for (Object item : ((Map<?, ?>) raw).values()) {
        String summary = summarizeValue(item);
        if (!summary.isEmpty()) return summary;
      }
      return "";
    }
    return value(raw);
  }

  private static String summarizeList(Object raw, int limit, int maxChars) {
    if (!(raw instanceof List)) return "";
    List<String> values = new ArrayList<>();
    for (Object item : (List<?>) raw) {
      String summary = summarizeValue(item);
      if (!summary.isEmpty()) values.add(summary);
      if (values.size() >= limit) break;
    }
    return joinLimited(values, "；", maxChars);
  }

  private static String joinLimited(List<String> values, String separator, int maxChars) {
    StringBuilder output = new StringBuilder();
    for (String value : values) {
      if (value == null || value.trim().isEmpty()) continue;
      String next = output.length() == 0 ? value.trim() : separator + value.trim();
      if (output.length() + next.length() > maxChars) {
        int remaining = maxChars - output.length();
        if (remaining > 1) {
          output.append(next, 0, Math.min(next.length(), remaining - 1)).append('…');
        }
        break;
      }
      output.append(next);
    }
    return output.toString();
  }

  private static boolean isEmptyCollection(Object raw) {
    if (raw == null) return true;
    if (raw instanceof List) return ((List<?>) raw).isEmpty();
    if (raw instanceof Map) return ((Map<?, ?>) raw).isEmpty();
    return value(raw).isEmpty();
  }

  private static String first(Map<?, ?> map, String... keys) {
    if (map == null) return "";
    for (String key : keys) {
      String value = value(map.get(key));
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  private static Map<?, ?> map(Object raw) {
    return raw instanceof Map ? (Map<?, ?>) raw : Collections.emptyMap();
  }

  private static String argument(ToolArguments args, String key, String fallback) {
    return args == null ? fallback : args.getString(key, fallback);
  }

  private static String value(Object raw) {
    return raw == null ? "" : String.valueOf(raw).trim();
  }

  private static boolean bool(Object raw, boolean fallback) {
    return raw instanceof Boolean ? (Boolean) raw : fallback;
  }

  private static int integer(Object raw, int fallback) {
    return raw instanceof Number ? ((Number) raw).intValue() : fallback;
  }

  private static String formatIssues(ValidationResult result) {
    if (result == null) return "验证未通过";
    StringBuilder output = new StringBuilder();
    for (ValidationIssue issue : result.issues()) output.append("• ").append(issue.message()).append('\n');
    return output.toString().trim();
  }

  @Override
  protected void onCleared() {
    cancel();
    executor.shutdownNow();
  }
}
