package com.cscjapp.aiworkbench.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.cscjapp.aiworkbench.android.databinding.AiwActivityWorkbenchBinding;
import com.cscjapp.aiworkbench.api.AccessPolicy;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.SessionStore;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.UserDecisionRequest;
import com.cscjapp.aiworkbench.api.WorkbenchAction;
import com.cscjapp.aiworkbench.api.WorkbenchContextItem;
import com.cscjapp.aiworkbench.api.WorkbenchDefinition;
import com.cscjapp.aiworkbench.api.WorkbenchFactory;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.api.WorkbenchSdkConfig;
import com.cscjapp.aiworkbench.api.WorkbenchUiState;
import com.kongzue.dialogx.dialogs.BottomMenu;
import com.kongzue.dialogx.dialogs.MessageDialog;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.flexbox.JustifyContent;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** SDK-owned UI, structurally and behaviorally aligned with the reference compiler workbench. */
public final class AIWorkbenchActivity extends AppCompatActivity {
  private static final String EXTRA_PREFIX = "com.cscjapp.aiworkbench.";
  private static final int AUTO_SCROLL_BOTTOM_THRESHOLD = 1;
  private static final long STREAM_TEXT_FRAME_INTERVAL_NANOS = 33_000_000L;
  private static final long STREAM_STATE_MIN_VISIBLE_MS = 220L;
  private static final long STREAM_IDLE_THRESHOLD_MS = 10_000L;

  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Runnable toolTicker =
      new Runnable() {
        @Override
        public void run() {
          if (binding == null || binding.aiwToolRunningContainer.getVisibility() != View.VISIBLE) return;
          updateToolRunningText();
          uiHandler.postDelayed(this, 250L);
        }
      };
  private final Runnable thinkingTicker =
      new Runnable() {
        @Override
        public void run() {
          if (viewModel == null || !viewModel.tickThinking()) return;
          uiHandler.postDelayed(this, 450L);
        }
      };
  private final Runnable streamUiWakeup = this::scheduleStreamUiFrame;
  private final Runnable streamIdleTicker = this::renderStreamIdleState;
  private final Choreographer.FrameCallback streamUiFrameCallback = this::flushStreamUiFrame;
  private final ArrayDeque<WorkbenchViewModel.StreamUiUpdate> pendingStreamUiEvents =
      new ArrayDeque<>();

  private AiwActivityWorkbenchBinding binding;
  private WorkbenchViewModel viewModel;
  private WorkbenchItemAdapter adapter;
  private WorkbenchItemAdapter planAdapter;
  private ContextLabelAdapter contextAdapter;
  private WorkbenchDefinition definition;
  private WorkbenchLaunchRequest request;
  private WorkbenchSdkConfig sdkConfig;
  private ModelEndpoint endpoint;
  private WorkbenchUiState uiState;
  private DecisionBroker.Pending shownDecision;
  private WorkbenchViewModel.ToolRunningState latestToolRunning;
  private boolean submitAuthorizationPending;
  private boolean deepToggleAuthorizationPending;
  private boolean firstItemsEmission = true;
  private boolean streamUiFrameScheduled;
  private long lastStreamTextFrameNanos;
  private int visibleStreamRoundId;
  private String visibleStreamKind = "";
  private long visibleStreamSinceMs;
  private long lastValidStreamDeltaMs;
  private WorkbenchViewModel.StreamUiUpdate lastValidStreamUpdate;
  private List<WorkbenchUiItem> pendingFullItems;
  private boolean pendingFullItemsShouldScroll;
  private Cancellable uiStateSubscription = Cancellable.NONE;

  public static void putRequest(Intent intent, WorkbenchLaunchRequest request) {
    intent.putExtra(EXTRA_PREFIX + "definition", request.definitionId());
    intent.putExtra(EXTRA_PREFIX + "workspace", request.workspaceId());
    intent.putExtra(EXTRA_PREFIX + "demand", request.initialDemand());
    intent.putExtra(EXTRA_PREFIX + "artifact", request.selectedArtifact());
    intent.putExtra(EXTRA_PREFIX + "deep", request.deepThinking());
    for (Map.Entry<String, String> extra : request.extras().entrySet()) {
      intent.putExtra(EXTRA_PREFIX + "extra." + extra.getKey(), extra.getValue());
    }
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().setStatusBarColor(Color.parseColor("#0B1220"));
    getWindow().setNavigationBarColor(Color.parseColor("#0B1220"));
    binding = AiwActivityWorkbenchBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    binding.aiwIvBack.setOnClickListener(ignored -> finish());
    request = readRequest(getIntent());
    try {
      sdkConfig = AIWorkbench.config();
      WorkbenchFactory factory = sdkConfig.factories().get(request.definitionId());
      if (factory == null) throw new IllegalStateException("未注册工作台: " + request.definitionId());
      definition = factory.create(request);
      if (definition == null) throw new IllegalStateException("工作台定义不能为空");
      endpoint = sdkConfig.modelConfigProvider().currentModel(request);
      SessionStore store =
          sdkConfig.sessionStore() == null ? new FileSessionStore(this) : sdkConfig.sessionStore();
      viewModel =
          new ViewModelProvider(this, WorkbenchViewModelFactory.INSTANCE)
              .get(WorkbenchViewModel.class);
      viewModel.initialize(
          definition,
          request,
          endpoint,
          store,
          AIWorkbench.runtimeOptions().modelGatewayFactory().create(request, endpoint));
    } catch (Throwable error) {
      showInitializationError(error);
      return;
    }

    initializeReferenceUi();
    observe();
    refreshUiState();
    uiStateSubscription = definition.uiStateProvider().observe(
        effectiveRequest(), () -> runIfActive(this::refreshModelEndpoint));
    bindInteractions();
    authorizeOpen(savedInstanceState == null);
  }

  private void initializeReferenceUi() {
    binding.aiwTvDisclaimer.setText(sdkConfig.themeConfig().disclaimer());
    adapter = new WorkbenchItemAdapter(new ArrayList<>());
    planAdapter = new WorkbenchItemAdapter(new ArrayList<>());
    contextAdapter = new ContextLabelAdapter(new ArrayList<>());
    contextAdapter.setOnRemoveListener(item -> definition.host().handleAction(
        "remove_context", new ToolArguments(Collections.singletonMap("artifact_id", item.id()))));

    adapter.setOnThoughtItemClickListener(
        (item, position) -> {
          item.thoughtContentExpanded = !item.thoughtContentExpanded;
          adapter.notifyItemChanged(position);
          viewModel.persistUiState();
        });
    adapter.setOnReasonItemClickListener(
        (item, position) -> {
          if (!item.detailExpandable) return;
          item.detailExpanded = !item.detailExpanded;
          adapter.notifyItemChanged(position);
          viewModel.persistUiState();
        });
    adapter.setOnSummaryActionClickListener(
        (item, position) -> {
          if (item.actionVisible && !item.actionId.isEmpty()) {
            definition.host().handleAction(item.actionId, ToolArguments.empty());
          }
        });
    adapter.setOnItemLongClickListener((item, position) -> copyWorkbenchItem(item));
    planAdapter.setOnItemLongClickListener((item, position) -> copyWorkbenchItem(item));
    adapter.setOnPresentationStateChangedListener(item -> viewModel.persistUiState());
    planAdapter.setOnPresentationStateChangedListener(item -> viewModel.persistUiState());

    binding.aiwRvPlan.setLayoutManager(new LinearLayoutManager(this));
    binding.aiwRvPlan.setNestedScrollingEnabled(false);
    binding.aiwRvPlan.setAdapter(planAdapter);
    binding.aiwRvWorkbench.setLayoutManager(new LinearLayoutManager(this));
    binding.aiwRvWorkbench.setItemAnimator(null);
    binding.aiwRvWorkbench.setAdapter(adapter);
    FlexboxLayoutManager contextLayout = new FlexboxLayoutManager(this);
    contextLayout.setFlexDirection(FlexDirection.ROW);
    contextLayout.setJustifyContent(JustifyContent.FLEX_START);
    binding.aiwRvContextFiles.setLayoutManager(contextLayout);
    binding.aiwRvContextFiles.setAdapter(contextAdapter);
    binding.aiwRvWorkbench.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (isNearBottom(false)) hideNewUpdates();
          }
        });
  }

  private void bindInteractions() {
    binding.aiwTvClearHistory.setOnClickListener(ignored -> confirmClear());
    binding.aiwIvReport.setOnClickListener(ignored -> performHostAction("report"));
    binding.aiwBtnExplainCode.setOnClickListener(ignored -> performHostAction("select_model"));
    binding.aiwBtnContextQuick.setOnClickListener(ignored -> performHostAction("select_context"));
    binding.aiwImgContext.setOnClickListener(ignored -> performHostAction("select_context"));
    binding.aiwBtnDeepThinking.setOnClickListener(ignored -> toggleDeepThinking());
    binding.aiwBtnSend.setOnClickListener(
        ignored -> {
          if (Boolean.TRUE.equals(viewModel.running().getValue())) viewModel.cancel();
          else send();
        });
    binding.aiwTvWorkbenchNewUpdates.setOnClickListener(ignored -> scrollToBottom());
    binding.aiwContextUsageChip.setOnClickListener(ignored -> showContextUsageDialog());
  }

  private void observe() {
    viewModel
        .items()
        .observe(
            this,
            list -> {
              boolean shouldScroll = firstItemsEmission || isNearBottom(true);
              firstItemsEmission = false;
              drainStreamUiMailbox();
              List<WorkbenchUiItem> copied =
                  list == null ? new ArrayList<>() : new ArrayList<>(list);
              if (hasActiveStreamPresentation() && streamTargetsPresentInAdapter()) {
                pendingFullItems = copied;
                pendingFullItemsShouldScroll |= shouldScroll;
              } else {
                applyFullItems(copied, shouldScroll);
              }
            });
    viewModel
        .planItems()
        .observe(
            this,
            list -> {
              planAdapter.setNewData(list == null ? new ArrayList<>() : new ArrayList<>(list));
              binding.aiwRvPlan.setVisibility(list == null || list.isEmpty() ? View.GONE : View.VISIBLE);
            });
    viewModel
        .running()
        .observe(
            this,
            running -> {
              updateSendButtonState();
              adapter.setCodeAreaLocked(Boolean.TRUE.equals(running));
              uiHandler.removeCallbacks(thinkingTicker);
              if (Boolean.TRUE.equals(running)) {
                AIWorkbenchRunService.start(
                    getApplicationContext(), "AI 正在理解需求", getIntent());
                uiHandler.post(thinkingTicker);
              } else {
                AIWorkbenchRunService.stop(getApplicationContext());
              }
            });
    viewModel
        .deepThinking()
        .observe(
            this,
            enabled -> binding.aiwBtnDeepThinking.setSelected(Boolean.TRUE.equals(enabled)));
    viewModel.toolRunning().observe(this, this::renderToolRunning);
    viewModel.contextUsage().observe(this, this::renderContextUsage);
    viewModel.streamUiUpdates().observe(this, this::requestStreamUiRefresh);
    viewModel.decisions.pending().observe(this, this::showDecision);
  }

  private void requestStreamUiRefresh(WorkbenchViewModel.StreamUiUpdate update) {
    if (adapter == null || viewModel == null) return;
    drainStreamUiMailbox();
  }

  private void drainStreamUiMailbox() {
    if (viewModel == null) return;
    for (WorkbenchViewModel.StreamUiUpdate update : viewModel.drainStreamUiUpdates()) {
      enqueueStreamUiUpdate(update);
    }
  }

  private void enqueueStreamUiUpdate(WorkbenchViewModel.StreamUiUpdate update) {
    if (update == null) return;
    if (update.terminal) {
      stopStreamIdleTicker();
      pendingStreamUiEvents.addLast(update);
      scheduleStreamUiFrame();
      return;
    }
    if (update.validDelta) {
      long now = SystemClock.elapsedRealtime();
      lastValidStreamDeltaMs = update.timestampMs > 0L ? update.timestampMs : now;
      lastValidStreamUpdate = update;
      uiHandler.removeCallbacks(streamIdleTicker);
      long remaining = streamIdleDelayMs(now, lastValidStreamDeltaMs);
      if (remaining == 0L) uiHandler.post(streamIdleTicker);
      else uiHandler.postDelayed(streamIdleTicker, remaining);
    }
    WorkbenchViewModel.StreamUiUpdate last = pendingStreamUiEvents.peekLast();
    if (last != null
        && last.roundId == update.roundId
        && last.kind.equals(update.kind)) {
      pendingStreamUiEvents.removeLast();
      pendingStreamUiEvents.addLast(mergeStreamUiUpdates(last, update));
    } else {
      pendingStreamUiEvents.addLast(update);
    }
    scheduleStreamUiFrame();
  }

  private void scheduleStreamUiFrame() {
    if (streamUiFrameScheduled) return;
    streamUiFrameScheduled = true;
    Choreographer.getInstance().postFrameCallback(streamUiFrameCallback);
  }

  private void flushStreamUiFrame(long frameTimeNanos) {
    streamUiFrameScheduled = false;
    WorkbenchViewModel.StreamUiUpdate update = pendingStreamUiEvents.peekFirst();
    if (update == null) return;

    long now = SystemClock.elapsedRealtime();
    if (update.terminal) {
      long visibleFor = now - visibleStreamSinceMs;
      if (visibleStreamRoundId == update.roundId
          && !visibleStreamKind.isEmpty()
          && visibleFor < STREAM_STATE_MIN_VISIBLE_MS) {
        scheduleStreamUiWakeup(STREAM_STATE_MIN_VISIBLE_MS - visibleFor);
        return;
      }
      pendingStreamUiEvents.removeFirst();
      if (visibleStreamRoundId == update.roundId) {
        visibleStreamRoundId = 0;
        visibleStreamKind = "";
        visibleStreamSinceMs = 0L;
      }
      if (pendingFullItems != null) applyPendingFullItems();
      else if (adapter != null) adapter.notifyDataSetChanged();
      if (!pendingStreamUiEvents.isEmpty()) scheduleStreamUiFrame();
      return;
    }
    boolean progressTransition = isProgressKind(update.kind);
    boolean changingKind =
        progressTransition
            && visibleStreamRoundId == update.roundId
            && !visibleStreamKind.isEmpty()
            && !visibleStreamKind.equals(update.kind);
    long visibleFor = now - visibleStreamSinceMs;
    if (changingKind && visibleFor < STREAM_STATE_MIN_VISIBLE_MS) {
      scheduleStreamUiWakeup(STREAM_STATE_MIN_VISIBLE_MS - visibleFor);
      return;
    }
    int combinedMask = update.thoughtMask | update.reasonMask;
    long textFrameGapNanos = frameTimeNanos - lastStreamTextFrameNanos;
    if (!changingKind
        && (combinedMask & WorkbenchStreamPayload.TEXT_MASK) != 0
        && lastStreamTextFrameNanos != 0L
        && textFrameGapNanos < STREAM_TEXT_FRAME_INTERVAL_NANOS) {
      long waitNanos = STREAM_TEXT_FRAME_INTERVAL_NANOS - textFrameGapNanos;
      scheduleStreamUiWakeup(Math.max(1L, (waitNanos + 999_999L) / 1_000_000L));
      return;
    }

    pendingStreamUiEvents.removeFirst();
    if (progressTransition
        && (visibleStreamRoundId != update.roundId || !visibleStreamKind.equals(update.kind))) {
      visibleStreamRoundId = update.roundId;
      visibleStreamKind = update.kind;
      visibleStreamSinceMs = now;
    }
    if (update.thoughtMask != WorkbenchStreamPayload.NONE) {
      notifyWorkbenchItemChanged(
          update.thoughtItem, update.thoughtMask, update.thoughtSnapshot);
    }
    if (update.reasonMask != WorkbenchStreamPayload.NONE) {
      notifyWorkbenchItemChanged(
          update.reasonItem, update.reasonMask, update.reasonSnapshot);
    }
    if ((combinedMask & WorkbenchStreamPayload.TEXT_MASK) != 0) {
      lastStreamTextFrameNanos = frameTimeNanos;
    }
    if (update.autoScroll) {
      if (isNearBottom(true)) scrollToBottom();
      else showNewUpdates();
    }
    if (!pendingStreamUiEvents.isEmpty()) scheduleStreamUiFrame();
  }

  private void notifyWorkbenchItemChanged(
      WorkbenchUiItem item, int mask, WorkbenchStreamUiSnapshot snapshot) {
    if (item == null || adapter == null || mask == 0) return;
    int position = adapter.getData().indexOf(item);
    if (position >= 0) {
      adapter.notifyItemChanged(position, new WorkbenchStreamPayload(mask, snapshot));
    }
  }

  private void clearPendingStreamUiUpdates() {
    if (streamUiFrameScheduled) {
      Choreographer.getInstance().removeFrameCallback(streamUiFrameCallback);
      streamUiFrameScheduled = false;
    }
    uiHandler.removeCallbacks(streamUiWakeup);
    stopStreamIdleTicker();
    pendingStreamUiEvents.clear();
    pendingFullItems = null;
    pendingFullItemsShouldScroll = false;
    visibleStreamRoundId = 0;
    visibleStreamKind = "";
    visibleStreamSinceMs = 0L;
    lastStreamTextFrameNanos = 0L;
  }

  private WorkbenchViewModel.StreamUiUpdate mergeStreamUiUpdates(
      WorkbenchViewModel.StreamUiUpdate older,
      WorkbenchViewModel.StreamUiUpdate newer) {
    return new WorkbenchViewModel.StreamUiUpdate(
        newer.roundId,
        newer.sequence,
        newer.kind,
        newer.timestampMs,
        newer.thoughtItem == null ? older.thoughtItem : newer.thoughtItem,
        newer.thoughtSnapshot == null ? older.thoughtSnapshot : newer.thoughtSnapshot,
        older.thoughtMask | newer.thoughtMask,
        newer.reasonItem == null ? older.reasonItem : newer.reasonItem,
        newer.reasonSnapshot == null ? older.reasonSnapshot : newer.reasonSnapshot,
        older.reasonMask | newer.reasonMask,
        older.autoScroll || newer.autoScroll,
        older.validDelta || newer.validDelta,
        false);
  }

  private void renderStreamIdleState() {
    if (lastValidStreamUpdate == null) return;
    long idleMs = SystemClock.elapsedRealtime() - lastValidStreamDeltaMs;
    if (idleMs < STREAM_IDLE_THRESHOLD_MS) {
      uiHandler.postDelayed(streamIdleTicker, STREAM_IDLE_THRESHOLD_MS - idleMs);
      return;
    }
    WorkbenchStreamUiSnapshot reason = lastValidStreamUpdate.reasonSnapshot;
    if (reason != null) {
      long seconds = Math.max(10L, idleMs / 1000L);
      WorkbenchViewModel.StreamUiUpdate waiting =
          new WorkbenchViewModel.StreamUiUpdate(
              lastValidStreamUpdate.roundId,
              lastValidStreamUpdate.sequence,
              "WAITING",
              SystemClock.elapsedRealtime(),
              null,
              null,
              WorkbenchStreamPayload.NONE,
              lastValidStreamUpdate.reasonItem,
              reason.withContent("模型仍在处理 · 已等待 " + seconds + " 秒"),
              WorkbenchStreamPayload.CONTENT
                  | WorkbenchStreamPayload.COUNTER
                  | WorkbenchStreamPayload.AURA,
              false,
              false,
              false);
      enqueueStreamUiUpdate(waiting);
    }
    uiHandler.postDelayed(streamIdleTicker, 1000L);
  }

  static long streamIdleDelayMs(long nowMs, long lastDeltaMs) {
    long elapsed = Math.max(0L, nowMs - Math.max(0L, lastDeltaMs));
    return Math.max(0L, STREAM_IDLE_THRESHOLD_MS - elapsed);
  }

  private void stopStreamIdleTicker() {
    uiHandler.removeCallbacks(streamIdleTicker);
    lastValidStreamDeltaMs = 0L;
    lastValidStreamUpdate = null;
  }

  private boolean isProgressKind(String kind) {
    return "REASONING".equals(kind)
        || "INPUT".equals(kind)
        || "WRITE".equals(kind)
        || "RECEIVE".equals(kind)
        || "WAITING".equals(kind);
  }

  private boolean hasActiveStreamPresentation() {
    return !pendingStreamUiEvents.isEmpty() || !visibleStreamKind.isEmpty();
  }

  private boolean streamTargetsPresentInAdapter() {
    if (adapter == null) return false;
    List<WorkbenchUiItem> data = adapter.getData();
    for (WorkbenchViewModel.StreamUiUpdate update : pendingStreamUiEvents) {
      if (update.thoughtItem != null && !data.contains(update.thoughtItem)) return false;
      if (update.reasonItem != null && !data.contains(update.reasonItem)) return false;
    }
    return true;
  }

  private void scheduleStreamUiWakeup(long delayMs) {
    uiHandler.removeCallbacks(streamUiWakeup);
    uiHandler.postDelayed(streamUiWakeup, Math.max(1L, delayMs));
  }

  private void applyPendingFullItems() {
    if (pendingFullItems == null) return;
    List<WorkbenchUiItem> value = pendingFullItems;
    boolean shouldScroll = pendingFullItemsShouldScroll;
    pendingFullItems = null;
    pendingFullItemsShouldScroll = false;
    applyFullItems(value, shouldScroll);
  }

  private void applyFullItems(List<WorkbenchUiItem> value, boolean shouldScroll) {
    adapter.setNewData(value == null ? new ArrayList<>() : value);
    if (shouldScroll) scrollToBottom();
    else if (value != null && !value.isEmpty()) showNewUpdates();
  }

  private void authorizeOpen(boolean maySubmitInitialDemand) {
    sdkConfig
        .accessPolicy()
        .check(
            "open_workbench",
            effectiveRequest(),
            new AccessPolicy.Callback() {
              @Override
              public void allow() {
                runIfActive(
                    () -> {
                      refreshModelEndpoint();
                      if (maySubmitInitialDemand
                          && viewModel.markInitialSubmitted()
                          && !request.initialDemand().trim().isEmpty()) {
                        authorizeSubmit(request.initialDemand(), false);
                      }
                    });
              }

              @Override
              public void deny(String message, String action) {
                runIfActive(
                    () -> {
                      definition
                          .host()
                          .handleAction(
                              action,
                              new ToolArguments(Collections.singletonMap("message", message)));
                      finish();
                    });
              }
            });
  }

  private void send() {
    String text = binding.aiwEtDemand.getText() == null ? "" : binding.aiwEtDemand.getText().toString();
    if (text.trim().isEmpty()) {
      Toast.makeText(this, "请输入需求", Toast.LENGTH_SHORT).show();
      return;
    }
    authorizeSubmit(text.trim(), true);
  }

  private void authorizeSubmit(String demand, boolean clearInput) {
    if (submitAuthorizationPending) return;
    submitAuthorizationPending = true;
    updateSendButtonState();
    sdkConfig
        .accessPolicy()
        .check(
            "submit_demand",
            effectiveRequest(),
            new AccessPolicy.Callback() {
              @Override
              public void allow() {
                runIfActive(
                    () -> {
                      submitAuthorizationPending = false;
                      refreshModelEndpoint();
                      if (clearInput) binding.aiwEtDemand.setText("");
                      viewModel.submit(demand);
                      updateSendButtonState();
                    });
              }

              @Override
              public void deny(String message, String action) {
                runIfActive(
                    () -> {
                      submitAuthorizationPending = false;
                      updateSendButtonState();
                      if (Boolean.TRUE.equals(viewModel.deepThinking().getValue())
                          && "member".equals(action)) {
                        showDeepThinkingAccessDialog(message, action, () -> {
                          viewModel.setDeepThinking(false);
                          authorizeSubmit(demand, clearInput);
                        });
                        return;
                      }
                      definition
                          .host()
                          .handleAction(
                              action,
                              new ToolArguments(Collections.singletonMap("message", message)));
                    });
              }
            });
  }

  private void toggleDeepThinking() {
    if (uiState == null || !uiState.deepThinkingSupported()) {
      Toast.makeText(this, "当前模型不支持深度思考", Toast.LENGTH_SHORT).show();
      return;
    }
    boolean enabled = !Boolean.TRUE.equals(viewModel.deepThinking().getValue());
    if (!enabled) {
      viewModel.setDeepThinking(false);
      return;
    }
    if (deepToggleAuthorizationPending) return;
    deepToggleAuthorizationPending = true;
    updateSendButtonState();
    sdkConfig.accessPolicy().check(
        "enable_deep_thinking", request.withDeepThinking(true), new AccessPolicy.Callback() {
          @Override
          public void allow() {
            runIfActive(() -> {
              deepToggleAuthorizationPending = false;
              viewModel.setDeepThinking(true);
              updateSendButtonState();
            });
          }

          @Override
          public void deny(String message, String action) {
            runIfActive(() -> {
              deepToggleAuthorizationPending = false;
              viewModel.setDeepThinking(false);
              updateSendButtonState();
              showDeepThinkingAccessDialog(message, action, null);
            });
          }
        });
  }

  private void showDeepThinkingAccessDialog(String message, String action, @Nullable Runnable withoutDeep) {
    MessageDialog dialog = MessageDialog.build()
        .setTitle("温馨提示")
        .setMessage(message == null || message.trim().isEmpty()
            ? "开启深度思考功能,将会提供更加全面强大的辅助功能,该功能需要开通会员使用"
            : message)
        .setOkButton("去开通获取", (ignored, view) -> {
          definition.host().handleAction(action, ToolArguments.empty());
          return false;
        });
    dialog.setCancelButton("不使用深度思考", (ignored, view) -> {
      viewModel.setDeepThinking(false);
      if (withoutDeep != null) withoutDeep.run();
      return false;
    }).show();
  }

  private void refreshModelEndpoint() {
    try {
      endpoint = sdkConfig.modelConfigProvider().currentModel(effectiveRequest());
      viewModel.updateEndpoint(endpoint);
      refreshUiState();
    } catch (Throwable ignored) {
      // Keep the last valid endpoint; a submission will surface the transport error.
    }
  }

  private void refreshUiState() {
    try {
      uiState = definition.uiStateProvider().current(effectiveRequest(), endpoint);
    } catch (Throwable ignored) {
      uiState = null;
    }
    if (uiState == null) {
      uiState =
          WorkbenchUiState.builder()
              .projectName(definition.displayName())
              .modelLabel(endpoint == null ? "默认模型" : endpoint.modelId())
              .deepThinkingSupported(endpoint != null && endpoint.deepThinking())
              .build();
    }
    String projectName = uiState.projectName().isEmpty() ? definition.displayName() : uiState.projectName();
    binding.aiwTvProjectName.setText(projectName);
    String model = uiState.modelLabel().isEmpty() ? "默认模型" : uiState.modelLabel();
    binding.aiwBtnExplainCode.setText("模型：" + model);
    binding.aiwBtnDeepThinking.setVisibility(uiState.deepThinkingSupported() ? View.VISIBLE : View.GONE);
    if (!uiState.deepThinkingSupported()) viewModel.setDeepThinking(false);
    boolean report = uiState.reportVisible() || findAction("report") != null;
    binding.aiwIvReport.setVisibility(report ? View.VISIBLE : View.GONE);
    boolean context = uiState.contextSelectionVisible() || findAction("select_context") != null;
    binding.aiwBtnContextQuick.setVisibility(context ? View.VISIBLE : View.GONE);
    binding.aiwImgContext.setVisibility(View.GONE);
    List<WorkbenchContextItem> labels = new ArrayList<>(uiState.contextItems());
    if (labels.isEmpty() && request.selectedArtifact() != null && !request.selectedArtifact().trim().isEmpty()) {
      labels.add(new WorkbenchContextItem(
          request.selectedArtifact(), new File(request.selectedArtifact()).getName()));
    }
    contextAdapter.setNewData(labels);
    viewModel.setUserName(uiState.userName());
    adapter.setUserAvatarUrl(uiState.userAvatarUrl());
    updateSendButtonState();
  }

  private void performHostAction(String id) {
    WorkbenchAction action = findAction(id);
    if (action != null && action.kind() == WorkbenchAction.Kind.PROMPT) {
      authorizeSubmit(action.value(), false);
      return;
    }
    definition.host().handleAction(id, ToolArguments.empty());
  }

  @Nullable
  private WorkbenchAction findAction(String id) {
    List<WorkbenchAction> actions = definition.actions();
    if (actions == null) return null;
    for (WorkbenchAction action : actions) {
      if (action != null && id.equals(action.id())) return action;
    }
    return null;
  }

  private void updateSendButtonState() {
    if (binding == null || viewModel == null) return;
    boolean requesting = Boolean.TRUE.equals(viewModel.running().getValue());
    boolean blocked = submitAuthorizationPending || deepToggleAuthorizationPending;
    binding.aiwBtnSend.setImageResource(requesting ? R.drawable.aiw_ic_stop2 : R.drawable.aiw_ic_send);
    binding.aiwBtnSend.setContentDescription(requesting ? "停止当前任务" : "发送需求");
    binding.aiwEtDemand.setHint(requesting ? "任务运行中，可点击右侧按钮停止" : "发消息让 AI 帮你做任何事...");
    binding.aiwEtDemand.setEnabled(!requesting && !blocked);
    binding.aiwEtDemand.setCursorVisible(!requesting && !blocked);
    binding.aiwEtDemand.setAlpha(requesting || blocked ? 0.72f : 1f);
    setOptionEnabled(binding.aiwBtnDeepThinking, !requesting && !blocked);
    setOptionEnabled(binding.aiwBtnContextQuick, !requesting && !blocked);
    setOptionEnabled(binding.aiwBtnExplainCode, !requesting && !blocked);
    binding.aiwBtnSend.setEnabled(!blocked);
  }

  private static void setOptionEnabled(View view, boolean enabled) {
    view.setEnabled(enabled);
    view.setAlpha(enabled ? 1f : 0.45f);
  }

  private void renderToolRunning(WorkbenchViewModel.ToolRunningState state) {
    WorkbenchViewModel.ToolRunningState previous = latestToolRunning;
    latestToolRunning = state;
    if (state == null || !state.visible) {
      uiHandler.removeCallbacks(toolTicker);
      binding.aiwToolRunningLoadingView.stop();
      binding.aiwToolRunningContainer.setVisibility(View.GONE);
      return;
    }
    boolean stageChanged =
        previous == null
            || !previous.visible
            || !previous.name.equals(state.name)
            || !previous.verb.equals(state.verb);
    binding.aiwToolRunningContainer.setVisibility(View.VISIBLE);
    if (stageChanged) {
      binding.aiwToolRunningLoadingView.start();
      AIWorkbenchRunService.update(getApplicationContext(), state.name + " · " + state.verb);
    }
    updateToolRunningText();
    uiHandler.removeCallbacks(toolTicker);
    uiHandler.post(toolTicker);
  }

  private void updateToolRunningText() {
    WorkbenchViewModel.ToolRunningState state = latestToolRunning;
    if (state == null || !state.visible) return;
    long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - state.startedAtMs);
    binding.aiwTvToolRunningInfo.setText(
        state.name + " · " + state.verb + " " + formatElapsed(elapsed));
  }

  private static String formatElapsed(long elapsedMs) {
    long totalSeconds = elapsedMs / 1000L;
    long minutes = totalSeconds / 60L;
    long seconds = totalSeconds % 60L;
    long tenths = (elapsedMs % 1000L) / 100L;
    if (minutes > 0) return String.format(Locale.getDefault(), "%02d:%02d.%d", minutes, seconds, tenths);
    return seconds + "." + tenths + "s";
  }

  private void renderContextUsage(WorkbenchViewModel.ContextUsageState state) {
    if (state == null || state.totalTokens <= 0L) {
      binding.aiwContextUsageChip.setVisibility(View.GONE);
      return;
    }
    binding.aiwContextUsageChip.setVisibility(View.VISIBLE);
    binding.aiwTvContextUsagePercent.setText(state.usedPercent + "%");
    binding.aiwTvContextUsagePercent.setTextColor(Color.parseColor(contextUsageColor(state.usedPercent)));
    binding.aiwPbContextUsage.setProgress(state.usedPercent);
  }

  private static String contextUsageColor(int percent) {
    if (percent >= 72) return "#FCA5A5";
    if (percent >= 58) return "#FDE68A";
    return "#DCEBFF";
  }

  private void showContextUsageDialog() {
    WorkbenchViewModel.ContextUsageState state = viewModel.contextUsage().getValue();
    if (state == null) {
      MessageDialog.show("背景信息窗口", "当前还没有可展示的上下文统计。", "知道了");
      return;
    }
    String message =
        state.usedPercent
            + "% 已用（下一次模型请求预计上下文，剩余 "
            + (100 - state.usedPercent)
            + "%）\n已用 "
            + formatTokenCount(state.usedTokens)
            + " 标记，共 "
            + formatTokenCount(state.totalTokens)
            + "\n会话消息 "
            + formatTokenCount(state.messageTokens)
            + "，工具定义 "
            + formatTokenCount(state.toolsTokens)
            + "\n剩余 "
            + formatTokenCount(state.remainingTokens)
            + " 标记"
            + (state.restored ? "\n已恢复上次 Agent 背景信息。" : "")
            + (state.usedPercent >= 72
                ? "\n下一轮请求前会自动压缩背景信息。"
                : state.usedPercent >= 58
                    ? "\n上下文占用正在接近自动压缩阈值。"
                    : "\nAI 工作台会在接近窗口上限前自动压缩背景信息。");
    MessageDialog.show("背景信息窗口", message, "知道了");
  }

  private static String formatTokenCount(long value) {
    if (value <= 0L) return "0";
    return Math.max(1L, Math.round(value / 1000.0d)) + "k";
  }

  private void scrollToBottom() {
    if (adapter == null || adapter.getItemCount() == 0) return;
    hideNewUpdates();
    binding.aiwRvWorkbench.post(
        () -> {
          binding.aiwRvWorkbench.scrollToPosition(adapter.getItemCount() - 1);
          hideNewUpdates();
        });
  }

  private boolean isNearBottom(boolean requireIdle) {
    if (adapter == null || adapter.getItemCount() == 0) return true;
    if (requireIdle && binding.aiwRvWorkbench.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return false;
    RecyclerView.LayoutManager manager = binding.aiwRvWorkbench.getLayoutManager();
    if (!(manager instanceof LinearLayoutManager)) return true;
    int last = ((LinearLayoutManager) manager).findLastVisibleItemPosition();
    return last >= adapter.getItemCount() - 1 - AUTO_SCROLL_BOTTOM_THRESHOLD;
  }

  private void showNewUpdates() {
    binding.aiwTvWorkbenchNewUpdates.setVisibility(View.VISIBLE);
  }

  private void hideNewUpdates() {
    binding.aiwTvWorkbenchNewUpdates.setVisibility(View.GONE);
  }

  private void copyWorkbenchItem(WorkbenchUiItem item) {
    StringBuilder text = new StringBuilder();
    appendBlock(text, item.title, item.resolvedContent());
    appendBlock(text, "详情", item.detailContent);
    appendBlock(text, "代码", item.resolvedCodeBlock());
    appendBlock(text, item.diffTitle.isEmpty() ? "代码变更" : item.diffTitle, item.diffText);
    if (text.length() == 0) {
      Toast.makeText(this, "当前内容为空", Toast.LENGTH_SHORT).show();
      return;
    }
    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (clipboard == null) {
      Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
      return;
    }
    try {
      clipboard.setPrimaryClip(ClipData.newPlainText("workbench_item", text.toString().trim()));
      Toast.makeText(this, "已复制整条内容", Toast.LENGTH_SHORT).show();
    } catch (Exception ignored) {
      Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
    }
  }

  private static void appendBlock(StringBuilder output, String title, String content) {
    if (content == null || content.trim().isEmpty()) return;
    if (output.length() > 0) output.append("\n\n");
    if (title != null && !title.trim().isEmpty()) output.append(title.trim()).append("：\n");
    output.append(sanitizeDisplayText(content));
  }

  private static String sanitizeDisplayText(String text) {
    if (text == null || text.isEmpty()) return text;
    int marker = text.indexOf("[native_tool_calls]");
    return marker < 0 ? text.trim() : text.substring(0, marker).trim();
  }

  private void showDecision(DecisionBroker.Pending pending) {
    if (pending == null) {
      shownDecision = null;
      return;
    }
    if (pending == shownDecision) return;
    shownDecision = pending;
    UserDecisionRequest decision = pending.request;
    if (decision.options().size() > 2) {
      String[] labels = new String[decision.options().size()];
      for (int i = 0; i < labels.length; i++) labels[i] = decision.options().get(i).label();
      BottomMenu menu =
          BottomMenu.show(
              decision.title(),
              labels,
              (dialog, text, index) -> {
                viewModel.decisions.decide(pending, decision.options().get(index).id());
                return false;
              });
      if (decision.cancellable()) menu.setCancelButton("取消");
      else menu.setCancelable(false);
      menu.show();
      return;
    }
    MessageDialog dialog =
        MessageDialog.build()
            .setTitle(decision.title())
            .setMessage(decision.message())
            .setCancelable(decision.cancellable());
    if (!decision.options().isEmpty()) {
      dialog.setOkButton(
          decision.options().get(0).label(),
          (ignored, view) -> {
            viewModel.decisions.decide(pending, decision.options().get(0).id());
            return false;
          });
    }
    if (decision.options().size() > 1) {
      dialog.setOtherButton(
          decision.options().get(1).label(),
          (ignored, view) -> {
            viewModel.decisions.decide(pending, decision.options().get(1).id());
            return false;
          });
    }
    if (decision.cancellable()) {
      dialog.setCancelButton(
          "取消",
          (ignored, view) -> {
            viewModel.decisions.cancel(pending);
            return false;
          });
    }
    dialog.show();
  }

  private void confirmClear() {
    boolean requesting = Boolean.TRUE.equals(viewModel.running().getValue());
    MessageDialog.build()
        .setTitle("温馨提示")
        .setMessage(requesting
            ? "当前任务仍在运行。继续操作会先停止本轮任务，并清空该项目的历史对话，是否继续？"
            : "此操作将清空该项目的历史对话，是否继续？")
        .setOkButton(
            "确定",
            (dialog, view) -> {
              viewModel.clear();
              clearPendingStreamUiUpdates();
              binding.aiwEtDemand.setText("");
              definition.host().handleAction("clear_context", ToolArguments.empty());
              hideNewUpdates();
              Toast.makeText(this, "已清空当前工程的 AI 工作台历史会话", Toast.LENGTH_SHORT).show();
              return false;
            })
        .setCancelButton("取消")
        .show();
  }

  private void showInitializationError(Throwable error) {
    Throwable cause = error;
    while (cause != null && cause.getCause() != null) cause = cause.getCause();
    String message = cause == null || cause.getMessage() == null ? "工作台初始化失败" : cause.getMessage();
    MessageDialog.build()
        .setTitle("无法打开 AI 工作台")
        .setMessage(message)
        .setOkButton(
            "关闭",
            (dialog, view) -> {
              finish();
              return false;
            })
        .setCancelable(false)
        .show();
  }

  private void runIfActive(Runnable action) {
    runOnUiThread(
        () -> {
          if (!isFinishing() && !isDestroyed()) action.run();
        });
  }

  private WorkbenchLaunchRequest effectiveRequest() {
    boolean enabled = viewModel != null && Boolean.TRUE.equals(viewModel.deepThinking().getValue());
    return request.withDeepThinking(enabled);
  }

  private static WorkbenchLaunchRequest readRequest(Intent intent) {
    String definition = intent.getStringExtra(EXTRA_PREFIX + "definition");
    WorkbenchLaunchRequest.Builder builder =
        WorkbenchLaunchRequest.builder(definition == null ? "missing" : definition)
            .workspaceId(intent.getStringExtra(EXTRA_PREFIX + "workspace"))
            .initialDemand(intent.getStringExtra(EXTRA_PREFIX + "demand"))
            .selectedArtifact(intent.getStringExtra(EXTRA_PREFIX + "artifact"))
            .deepThinking(intent.getBooleanExtra(EXTRA_PREFIX + "deep", false));
    if (intent.getExtras() != null) {
      for (String key : intent.getExtras().keySet()) {
        if (key.startsWith(EXTRA_PREFIX + "extra.")) {
          builder.extra(
              key.substring((EXTRA_PREFIX + "extra.").length()),
              String.valueOf(intent.getExtras().get(key)));
        }
      }
    }
    return builder.build();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (viewModel == null || sdkConfig == null || request == null) return;
    refreshModelEndpoint();
  }

  @Override
  protected void onDestroy() {
    uiHandler.removeCallbacks(toolTicker);
    uiHandler.removeCallbacks(thinkingTicker);
    clearPendingStreamUiUpdates();
    uiStateSubscription.cancel();
    if (binding != null) binding.aiwToolRunningLoadingView.stop();
    super.onDestroy();
  }

  @Override
  protected void onPause() {
    if (viewModel != null) viewModel.persistUiState();
    super.onPause();
  }

  @Override
  public void finish() {
    if (viewModel != null && Boolean.TRUE.equals(viewModel.running().getValue())) {
      viewModel.cancel();
    }
    super.finish();
  }
}
