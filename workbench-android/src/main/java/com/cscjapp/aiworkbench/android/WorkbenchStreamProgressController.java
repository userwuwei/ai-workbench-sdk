package com.cscjapp.aiworkbench.android;

import com.cscjapp.aiworkbench.core.ModelStreamDelta;
import com.cscjapp.aiworkbench.core.ToolCallStreamDelta;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-round stream accumulator and reference-compatible counter-state classifier. */
final class WorkbenchStreamProgressController {
  private static final long TEXT_SNAPSHOT_INTERVAL_NANOS = 33_000_000L;
  enum Kind {
    NONE,
    REASONING,
    INPUT,
    WRITE,
    RECEIVE
  }

  static final class Snapshot {
    final Kind kind;
    final String titleStage;
    final String content;
    final String label;
    final long value;
    final String reasoningText;
    final boolean toolCallVisible;
    final boolean autoScroll;
    final String runningName;
    final String runningVerb;

    Snapshot(
        Kind kind,
        String titleStage,
        String content,
        String label,
        long value,
        String reasoningText,
        boolean toolCallVisible,
        boolean autoScroll,
        String runningName,
        String runningVerb) {
      this.kind = kind;
      this.titleStage = titleStage;
      this.content = content;
      this.label = label;
      this.value = Math.max(0L, value);
      this.reasoningText = reasoningText;
      this.toolCallVisible = toolCallVisible;
      this.autoScroll = autoScroll;
      this.runningName = runningName;
      this.runningVerb = runningVerb;
    }

    static Snapshot empty(String reasoningText) {
      return new Snapshot(
          Kind.NONE, "模型输出", "", "", 0L, reasoningText, false, false, "", "");
    }
  }

  private final StringBuilder content = new StringBuilder();
  private final StringBuilder reasoning = new StringBuilder();
  private final Map<Integer, ToolCallAccumulator> calls = new LinkedHashMap<>();
  private final LegacyWriteStreamInspector legacyInspector = new LegacyWriteStreamInspector();
  private String visibleContentCache = "";
  private String reasoningTextCache = "";
  private long lastContentSnapshotNanos;
  private long lastReasoningSnapshotNanos;
  private int visibleSnapshotBuildCount;
  private boolean nativeToolAutoScrollSent;
  private boolean legacyWriteAutoScrollSent;

  void reset() {
    content.setLength(0);
    reasoning.setLength(0);
    calls.clear();
    legacyInspector.reset();
    visibleContentCache = "";
    reasoningTextCache = "";
    lastContentSnapshotNanos = 0L;
    lastReasoningSnapshotNanos = 0L;
    visibleSnapshotBuildCount = 0;
    nativeToolAutoScrollSent = false;
    legacyWriteAutoScrollSent = false;
  }

  Snapshot append(ModelStreamDelta delta, boolean nativeToolsEnabled) {
    if (delta == null) return Snapshot.empty(reasoningTextCache);
    boolean hasContentDelta = !delta.content().isEmpty();
    boolean hasReasoningDelta = !delta.reasoning().isEmpty();
    boolean hasToolDelta = !delta.toolCalls().isEmpty();
    if (hasContentDelta) {
      content.append(delta.content());
      legacyInspector.append(delta.content());
    }
    if (hasReasoningDelta) reasoning.append(delta.reasoning());
    for (ToolCallStreamDelta call : delta.toolCalls()) append(call);
    long now = System.nanoTime();
    String reasoningText =
        snapshotReasoning(
            now,
            !delta.reasoning().isEmpty()
                && (reasoningTextCache.isEmpty()
                    || delta.reasoning().indexOf('\n') >= 0));

    // Classification follows this delta, not the accumulated round. Accumulators remain useful
    // for exact counters and fragmented tool-call assembly, but must not pin later reasoning or
    // visible content to a stale RECEIVE/WRITE state.
    if (hasToolDelta) {
      if (content.length() > 0) {
        snapshotVisibleContent(
            now, visibleContentCache.isEmpty() || hasContentDelta);
      }
      return nativeToolSnapshot(reasoningText);
    }

    LegacyWriteProgress write = legacyInspector.snapshot();
    if (hasContentDelta && write != null && legacyInspector.wasWriteProtocolInLastAppend()) {
      boolean autoScroll = !legacyWriteAutoScrollSent;
      legacyWriteAutoScrollSent = true;
      return new Snapshot(
          Kind.WRITE,
          "准备执行工具",
          write.statusText,
          "已写入",
          write.receivedChars,
          reasoningText,
          false,
          autoScroll,
          "接收工具参数",
          "接收中");
    }

    if (hasContentDelta) {
      String visibleContent =
          snapshotVisibleContent(
              now,
              visibleContentCache.isEmpty()
                  || delta.content().indexOf('\n') >= 0
                  || !delta.toolCalls().isEmpty());
      return new Snapshot(
          Kind.INPUT,
          "模型输出",
          visibleContent.isEmpty() ? "模型响应中" : visibleContent,
          "已输入",
          content.length(),
          reasoningText,
          false,
          delta.content().contains("\n"),
          "模型响应中",
          "处理中");
    }

    if (hasReasoningDelta) {
      return new Snapshot(
          Kind.REASONING,
          "模型输出",
          "模型响应中",
          "已思考",
          reasoning.length(),
          reasoningText,
          false,
          false,
          "模型响应中",
          "处理中");
    }
    return Snapshot.empty(reasoningText);
  }

  boolean hasReasoning() {
    return reasoning.length() > 0;
  }

  int visibleSnapshotBuildCountForTest() {
    return visibleSnapshotBuildCount;
  }

  private Snapshot nativeToolSnapshot(String reasoningText) {
    String toolName = primaryToolName();
    boolean write = isWriteTool(toolName);
    long argumentsLength = argumentsLength();
    String visibleContent = visibleContentCache;
    boolean autoScroll = !nativeToolAutoScrollSent;
    nativeToolAutoScrollSent = true;
    return new Snapshot(
        write ? Kind.WRITE : Kind.RECEIVE,
        "准备执行工具",
        visibleContent.isEmpty() ? "模型响应中" : visibleContent,
        write ? "已写入" : "已接收",
        write ? argumentsLength : Math.max(content.length(), argumentsLength),
        reasoningText,
        true,
        autoScroll,
        write ? "接收工具参数" : "模型响应中",
        write ? "接收中" : "处理中");
  }

  private void append(ToolCallStreamDelta delta) {
    if (delta == null) return;
    ToolCallAccumulator call =
        calls.computeIfAbsent(delta.index(), ignored -> new ToolCallAccumulator());
    call.id = appendStable(call.id, delta.id());
    call.name = appendStable(call.name, delta.name());
    call.argumentsLength += delta.arguments().length();
  }

  private String primaryToolName() {
    for (ToolCallAccumulator call : calls.values()) {
      if (!call.name.isEmpty()) return call.name;
    }
    return "";
  }

  private long argumentsLength() {
    long length = 0L;
    for (ToolCallAccumulator call : calls.values()) length += call.argumentsLength;
    return length;
  }

  private static boolean isWriteTool(String name) {
    return "create_file".equals(name)
        || "search_replace".equals(name)
        || "rewrite".equals(name);
  }

  private static String appendStable(String current, String delta) {
    if (delta == null || delta.isEmpty()) return current;
    if (current == null || current.isEmpty()) return delta;
    if (current.equals(delta) || current.endsWith(delta) || current.startsWith(delta)) return current;
    if (delta.startsWith(current)) return delta;
    return current + delta;
  }

  private static String sanitize(String value) {
    int marker = value.indexOf("[native_tool_calls]");
    return marker < 0 ? value : value.substring(0, marker);
  }

  private String snapshotVisibleContent(long now, boolean force) {
    if (force
        || lastContentSnapshotNanos == 0L
        || now - lastContentSnapshotNanos >= TEXT_SNAPSHOT_INTERVAL_NANOS) {
      visibleContentCache = sanitize(content.toString()).trim();
      lastContentSnapshotNanos = now;
      visibleSnapshotBuildCount++;
    }
    return visibleContentCache;
  }

  private String snapshotReasoning(long now, boolean force) {
    if (reasoning.length() == 0) return "";
    if (force
        || lastReasoningSnapshotNanos == 0L
        || now - lastReasoningSnapshotNanos >= TEXT_SNAPSHOT_INTERVAL_NANOS) {
      reasoningTextCache = reasoning.toString();
      lastReasoningSnapshotNanos = now;
    }
    return reasoningTextCache;
  }

  private static final class ToolCallAccumulator {
    String id = "";
    String name = "";
    long argumentsLength;
  }

  private static final class LegacyWriteProgress {
    final String statusText;
    final long receivedChars;

    LegacyWriteProgress(String statusText, long receivedChars) {
      this.statusText = statusText;
      this.receivedChars = receivedChars;
    }
  }

  /**
   * Incremental JSON string tokenizer for the legacy protocol. It consumes each character once and
   * counts decoded target-field characters without rescanning the accumulated model response.
   */
  private static final class LegacyWriteStreamInspector {
    private final StringBuilder token = new StringBuilder();
    private String pendingKey = "";
    private String valueKey = "";
    private String completedToken = "";
    private String tool = "";
    private String path = "";
    private boolean inString;
    private boolean escaping;
    private boolean valueString;
    private boolean expectingValue;
    private boolean hasNextAction;
    private int unicodeRemaining;
    private int unicodeValue;
    private long contentChars;
    private long newChars;
    private int containerDepth;
    private boolean targetValueActivity;
    private boolean lastAppendWriteProtocol;

    void reset() {
      token.setLength(0);
      pendingKey = "";
      valueKey = "";
      completedToken = "";
      tool = "";
      path = "";
      inString = false;
      escaping = false;
      valueString = false;
      expectingValue = false;
      hasNextAction = false;
      unicodeRemaining = 0;
      unicodeValue = 0;
      contentChars = 0L;
      newChars = 0L;
      containerDepth = 0;
      targetValueActivity = false;
      lastAppendWriteProtocol = false;
    }

    void append(String delta) {
      if (delta == null || delta.isEmpty()) return;
      boolean writeBefore = hasNextAction && isWriteTool(tool);
      int depthBefore = containerDepth;
      targetValueActivity = false;
      for (int index = 0; index < delta.length(); index++) accept(delta.charAt(index));
      boolean writeAfter = hasNextAction && isWriteTool(tool);
      lastAppendWriteProtocol =
          writeAfter
              && (!writeBefore
                  || targetValueActivity
                  || depthBefore > 0
                  || containerDepth > 0);
    }

    boolean wasWriteProtocolInLastAppend() {
      return lastAppendWriteProtocol;
    }

    LegacyWriteProgress snapshot() {
      if (!hasNextAction || !isWriteTool(tool)) return null;
      String fileName = "目标文件";
      if (!path.isEmpty()) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        fileName = slash >= 0 ? path.substring(slash + 1) : path;
        if (fileName.isEmpty()) fileName = "目标文件";
      }
      long count = "search_replace".equals(tool) ? newChars : contentChars;
      return new LegacyWriteProgress("正在向" + fileName + "写入代码", count);
    }

    private void accept(char value) {
      if (!inString) {
        acceptOutsideString(value);
        return;
      }
      if (unicodeRemaining > 0) {
        unicodeValue = (unicodeValue << 4) | hex(value);
        unicodeRemaining--;
        if (unicodeRemaining == 0) appendDecoded((char) unicodeValue, false);
        return;
      }
      if (escaping) {
        escaping = false;
        if (value == 'u') {
          incrementTargetCount();
          unicodeRemaining = 4;
          unicodeValue = 0;
        } else {
          appendDecoded(unescape(value), true);
        }
        return;
      }
      if (value == '\\') {
        escaping = true;
        return;
      }
      if (value == '"') {
        finishString();
        return;
      }
      appendDecoded(value, true);
    }

    private void acceptOutsideString(char value) {
      if (value == '"') {
        inString = true;
        valueString = expectingValue && !pendingKey.isEmpty();
        valueKey = valueString ? pendingKey : "";
        token.setLength(0);
        return;
      }
      if (Character.isWhitespace(value)) return;
      if (value == ':' && !completedToken.isEmpty()) {
        pendingKey = completedToken;
        hasNextAction |= "next_action".equals(pendingKey);
        completedToken = "";
        expectingValue = true;
        return;
      }
      if (value == '{' || value == '[') {
        containerDepth++;
        if (expectingValue) {
          expectingValue = false;
          pendingKey = "";
        }
        completedToken = "";
        return;
      }
      if (value == ',' || value == '}' || value == ']') {
        if ((value == '}' || value == ']') && containerDepth > 0) containerDepth--;
        completedToken = "";
        expectingValue = false;
        pendingKey = "";
      }
    }

    private void finishString() {
      inString = false;
      escaping = false;
      unicodeRemaining = 0;
      String decoded = token.toString();
      if (valueString) {
        if ("tool".equals(valueKey)) tool = decoded;
        else if ("path".equals(valueKey)) path = decoded;
        valueString = false;
        valueKey = "";
        pendingKey = "";
        expectingValue = false;
        completedToken = "";
      } else {
        completedToken = decoded;
      }
      token.setLength(0);
    }

    private void appendDecoded(char value, boolean count) {
      if (valueString && ("tool".equals(valueKey) || "path".equals(valueKey))) {
        token.append(value);
      } else if (!valueString) {
        token.append(value);
      }
      if (count) incrementTargetCount();
    }

    private void incrementTargetCount() {
      if (!valueString) return;
      if ("content".equals(valueKey)) {
        contentChars++;
        targetValueActivity = true;
      } else if ("new".equals(valueKey)) {
        newChars++;
        targetValueActivity = true;
      }
    }

    private static char unescape(char value) {
      if (value == 'n') return '\n';
      if (value == 'r') return '\r';
      if (value == 't') return '\t';
      if (value == 'b') return '\b';
      if (value == 'f') return '\f';
      return value;
    }

    private static int hex(char value) {
      if (value >= '0' && value <= '9') return value - '0';
      if (value >= 'a' && value <= 'f') return 10 + value - 'a';
      if (value >= 'A' && value <= 'F') return 10 + value - 'A';
      return 0;
    }
  }
}
