package com.cscjapp.aiworkbench.android;

import com.chad.library.adapter.base.entity.MultiItemEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete, versionable presentation state for one reference-workbench timeline card. */
final class WorkbenchUiItem implements MultiItemEntity {
  static final int TYPE_PLAN = 1;
  static final int TYPE_THOUGHT = 2;
  static final int TYPE_SUMMARY = 3;
  static final int TYPE_REASON = 4;
  static final int TYPE_EDIT_NOTICE = 5;
  static final int TYPE_USER_DEMAND = 6;
  static final int TYPE_BROWSER_TEST = 7;

  static final String STATUS_NORMAL = "normal";
  static final String STATUS_SUCCESS = "success";
  static final String STATUS_WARNING = "warning";
  static final String STATUS_ERROR = "error";
  static final String WAITING_EFFECT_NONE = "none";
  static final String WAITING_EFFECT_TOOL_AURA = "tool_aura";

  final int type;
  String title;
  String content;
  int summaryIconResId;
  boolean errorState;
  String statusLevel = STATUS_NORMAL;
  String waitingEffect = WAITING_EFFECT_NONE;
  /** Runtime-only phase anchor. Historical cards must never restore as actively animating. */
  transient long waitingEffectStartedAtMs;
  String codeBlock = "";
  List<String> steps = new ArrayList<>();
  boolean codeExpanded;
  boolean thoughtContentExpanded;
  boolean contentExpanded;
  boolean detailExpanded;
  boolean detailExpandable;
  boolean showProgressCounter;
  long progressCounterValue;
  String progressCounterLabel = "";
  String detailContent = "";
  boolean actionVisible;
  boolean actionEnabled = true;
  String actionText = "";
  String actionId = "";
  boolean diffVisible;
  boolean diffExpanded;
  String diffTitle = "";
  String diffMeta = "";
  String diffText = "";
  String browserTestStatus = "";
  String browserTestMeta = "";
  private transient StringBuilder streamedContent;
  private transient StringBuilder streamedCodeBlock;

  private WorkbenchUiItem(int type, String title, String content) {
    this.type = type;
    this.title = safe(title);
    this.content = safe(content);
  }

  static WorkbenchUiItem plan(String progress, List<String> steps) {
    WorkbenchUiItem item = new WorkbenchUiItem(TYPE_PLAN, progress, "");
    item.steps = copyStrings(steps);
    item.detailExpanded = true;
    return item;
  }

  static WorkbenchUiItem thought(String title, String content, String codeBlock) {
    WorkbenchUiItem item = new WorkbenchUiItem(TYPE_THOUGHT, title, content);
    item.codeBlock = safe(codeBlock);
    return item;
  }

  static WorkbenchUiItem summary(String title, String content) {
    return new WorkbenchUiItem(TYPE_SUMMARY, title, content);
  }

  static WorkbenchUiItem reason(String title, String content) {
    return new WorkbenchUiItem(TYPE_REASON, title, content);
  }

  static WorkbenchUiItem editNotice(String title, String content) {
    return new WorkbenchUiItem(TYPE_EDIT_NOTICE, title, content);
  }

  static WorkbenchUiItem userDemand(String title, String content) {
    return new WorkbenchUiItem(TYPE_USER_DEMAND, title, content);
  }

  static WorkbenchUiItem browserTest(String title, String content, List<String> steps) {
    WorkbenchUiItem item = new WorkbenchUiItem(TYPE_BROWSER_TEST, title, content);
    item.steps = copyStrings(steps);
    return item;
  }

  @Override
  public int getItemType() {
    return type;
  }

  Map<String, Object> toMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("type", type);
    value.put("title", title);
    value.put("content", resolvedContent());
    value.put("summaryIconResId", summaryIconResId);
    value.put("errorState", errorState);
    value.put("statusLevel", statusLevel);
    value.put("waitingEffect", waitingEffect);
    value.put("codeBlock", resolvedCodeBlock());
    value.put("steps", new ArrayList<>(steps));
    value.put("codeExpanded", codeExpanded);
    value.put("thoughtContentExpanded", thoughtContentExpanded);
    value.put("contentExpanded", contentExpanded);
    value.put("detailExpanded", detailExpanded);
    value.put("detailExpandable", detailExpandable);
    value.put("showProgressCounter", showProgressCounter);
    value.put("progressCounterValue", progressCounterValue);
    value.put("progressCounterLabel", progressCounterLabel);
    value.put("detailContent", detailContent);
    value.put("actionVisible", actionVisible);
    value.put("actionEnabled", actionEnabled);
    value.put("actionText", actionText);
    value.put("actionId", actionId);
    value.put("diffVisible", diffVisible);
    value.put("diffExpanded", diffExpanded);
    value.put("diffTitle", diffTitle);
    value.put("diffMeta", diffMeta);
    value.put("diffText", diffText);
    value.put("browserTestStatus", browserTestStatus);
    value.put("browserTestMeta", browserTestMeta);
    return value;
  }

  static WorkbenchUiItem from(Object raw) {
    if (!(raw instanceof Map)) return null;
    Map<?, ?> value = (Map<?, ?>) raw;
    int type = integer(value.get("type"), TYPE_SUMMARY);
    // Compatibility with the first SDK history schema, which used string card types.
    Object rawType = value.get("type");
    if (rawType instanceof String) {
      String legacy = (String) rawType;
      type = "user".equals(legacy) ? TYPE_USER_DEMAND : "tool".equals(legacy) ? TYPE_EDIT_NOTICE : TYPE_SUMMARY;
    }
    WorkbenchUiItem item = new WorkbenchUiItem(type, string(value.get("title")), string(value.get("content")));
    item.summaryIconResId = integer(value.get("summaryIconResId"), 0);
    item.errorState = bool(value.get("errorState"), bool(value.get("error"), false));
    item.statusLevel = string(value.get("statusLevel"), item.errorState ? STATUS_ERROR : STATUS_NORMAL);
    item.waitingEffect = WAITING_EFFECT_NONE;
    item.codeBlock = string(value.get("codeBlock"));
    item.steps = copyObjects(value.get("steps"));
    item.codeExpanded = bool(value.get("codeExpanded"), false);
    item.thoughtContentExpanded = bool(value.get("thoughtContentExpanded"), false);
    item.contentExpanded = bool(value.get("contentExpanded"), false);
    item.detailExpanded = bool(value.get("detailExpanded"), false);
    item.detailExpandable = bool(value.get("detailExpandable"), false);
    item.showProgressCounter = false;
    item.progressCounterValue = 0L;
    item.progressCounterLabel = "";
    item.detailContent = string(value.get("detailContent"));
    item.actionVisible = bool(value.get("actionVisible"), false);
    item.actionEnabled = bool(value.get("actionEnabled"), true);
    item.actionText = string(value.get("actionText"));
    item.actionId = string(value.get("actionId"));
    item.diffVisible = bool(value.get("diffVisible"), false);
    item.diffExpanded = bool(value.get("diffExpanded"), false);
    item.diffTitle = string(value.get("diffTitle"));
    item.diffMeta = string(value.get("diffMeta"));
    item.diffText = string(value.get("diffText"));
    item.browserTestStatus = string(value.get("browserTestStatus"));
    item.browserTestMeta = string(value.get("browserTestMeta"));
    return item;
  }

  private static List<String> copyStrings(List<String> source) {
    return source == null ? new ArrayList<>() : new ArrayList<>(source);
  }

  private static List<String> copyObjects(Object raw) {
    if (!(raw instanceof List)) return new ArrayList<>();
    List<String> result = new ArrayList<>();
    for (Object item : (List<?>) raw) result.add(string(item));
    return result;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String string(Object value, String fallback) {
    String result = string(value);
    return result.isEmpty() ? fallback : result;
  }

  private static boolean bool(Object value, boolean fallback) {
    return value instanceof Boolean ? (Boolean) value : fallback;
  }

  private static int integer(Object value, int fallback) {
    return value instanceof Number ? ((Number) value).intValue() : fallback;
  }

  private static long longValue(Object value, long fallback) {
    return value instanceof Number ? ((Number) value).longValue() : fallback;
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  synchronized void appendStreamContent(String delta) {
    if (delta == null || delta.isEmpty()) return;
    if (streamedContent == null) streamedContent = new StringBuilder();
    streamedContent.append(delta);
  }

  synchronized void appendStreamCodeBlock(String delta) {
    if (delta == null || delta.isEmpty()) return;
    if (streamedCodeBlock == null) streamedCodeBlock = new StringBuilder();
    streamedCodeBlock.append(delta);
  }

  synchronized String resolvedContent() {
    return streamedContent == null ? safe(content) : streamedContent.toString();
  }

  synchronized String resolvedCodeBlock() {
    return streamedCodeBlock == null ? safe(codeBlock) : streamedCodeBlock.toString();
  }

  synchronized void clearStreamedContent() {
    streamedContent = null;
  }

  synchronized void clearStreamedCodeBlock() {
    streamedCodeBlock = null;
  }
}
