package com.cscjapp.aiworkbench.android;

/** Immutable presentation snapshot for one high-frequency stream card update. */
final class WorkbenchStreamUiSnapshot {
  final String title;
  final String content;
  final String codeBlock;
  final boolean codeExpanded;
  final boolean thoughtContentExpanded;
  final boolean contentExpanded;
  final boolean showProgressCounter;
  final String progressCounterLabel;
  final long progressCounterValue;
  final boolean auraActive;
  final long auraStartedAtMs;

  private WorkbenchStreamUiSnapshot(
      String title,
      String content,
      String codeBlock,
      boolean codeExpanded,
      boolean thoughtContentExpanded,
      boolean contentExpanded,
      boolean showProgressCounter,
      String progressCounterLabel,
      long progressCounterValue,
      boolean auraActive,
      long auraStartedAtMs) {
    this.title = safe(title);
    this.content = safe(content);
    this.codeBlock = safe(codeBlock);
    this.codeExpanded = codeExpanded;
    this.thoughtContentExpanded = thoughtContentExpanded;
    this.contentExpanded = contentExpanded;
    this.showProgressCounter = showProgressCounter;
    this.progressCounterLabel = safe(progressCounterLabel);
    this.progressCounterValue = Math.max(0L, progressCounterValue);
    this.auraActive = auraActive;
    this.auraStartedAtMs = Math.max(0L, auraStartedAtMs);
  }

  static WorkbenchStreamUiSnapshot capture(WorkbenchUiItem item) {
    if (item == null) return null;
    return new WorkbenchStreamUiSnapshot(
        item.title,
        item.content,
        item.codeBlock,
        item.codeExpanded,
        item.thoughtContentExpanded,
        item.contentExpanded,
        item.showProgressCounter,
        item.progressCounterLabel,
        item.progressCounterValue,
        WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA.equals(item.waitingEffect),
        item.waitingEffectStartedAtMs);
  }

  WorkbenchStreamUiSnapshot withContent(String value) {
    return new WorkbenchStreamUiSnapshot(
        title,
        value,
        codeBlock,
        codeExpanded,
        thoughtContentExpanded,
        contentExpanded,
        showProgressCounter,
        progressCounterLabel,
        progressCounterValue,
        auraActive,
        auraStartedAtMs);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
