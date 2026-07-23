package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.ToolArguments;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the bounded, request-only view of completed large write turns. */
final class AgentHistoryRequestProjection {
  static final int LARGE_WRITE_ARGUMENT_CHARS = 2048;
  private static final int MAX_RETRY_ITEMS = 8;
  private static final int MAX_RETRY_TEXT_CHARS = 1200;
  private static final int MAX_ERROR_MESSAGE_CHARS = 1600;
  private static final int MAX_REPAIR_PAYLOAD_CHARS = 48 * 1024;
  private static final int MAX_REPAIR_PREVIEW_CHARS = 512;
  private static final Gson GSON = new Gson();

  private AgentHistoryRequestProjection() {}

  static List<AgentMessage> project(List<AgentMessage> source) {
    List<AgentMessage> safe = AgentHistory.sanitize(source);
    if (safe.isEmpty()) return safe;
    List<AgentMessage> out = new ArrayList<>(safe.size());
    Map<String, Integer> latestSuccessfulWrites = latestSuccessfulWriteIndexes(safe);
    int latestBrowserFailure = latestBrowserFailureIndex(safe);
    boolean anyChanged = false;
    for (int index = 0; index < safe.size(); ) {
      AgentMessage message = safe.get(index);
      if (message.role() != AgentMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) {
        out.add(message);
        index++;
        continue;
      }

      Map<String, AgentMessage> resultsByCallId = new LinkedHashMap<>();
      int cursor = index + 1;
      while (cursor < safe.size() && safe.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage result = safe.get(cursor++);
        resultsByCallId.put(result.toolCallId(), result);
      }
      Map<String, Projection> projections = new LinkedHashMap<>();
      List<AgentToolCall> calls = new ArrayList<>(message.toolCalls().size());
      for (AgentToolCall call : message.toolCalls()) {
        Projection projection = create(
            call,
            resultsByCallId.get(call.id()),
            staleReadPaths(call, latestSuccessfulWrites, index),
            index == latestBrowserFailure);
        if (projection == null) calls.add(call);
        else {
          calls.add(new AgentToolCall(call.id(), call.name(), projection.arguments));
          projections.put(call.id(), projection);
          anyChanged = true;
        }
      }
      out.add(projections.isEmpty() ? message : AgentMessage.assistant(message.content(), calls));
      while (++index < cursor) {
        AgentMessage result = safe.get(index);
        Projection projection = projections.get(result.toolCallId());
        out.add(
            projection == null
                ? result
                : AgentMessage.tool(result.toolCallId(), result.name(), projection.resultJson));
      }
    }
    return anyChanged ? Collections.unmodifiableList(out) : safe;
  }

  static String latestManagedPlanSummary(List<AgentMessage> source) {
    List<AgentMessage> safe = AgentHistory.sanitize(source);
    JsonObject latestPlan = null;
    JsonObject latestState = null;
    for (AgentMessage message : safe) {
      if (message.role() != AgentMessage.Role.TOOL) continue;
      JsonObject root = parseObject(message.content());
      JsonObject data = root == null ? null : object(root.get("data"));
      if (data == null) continue;
      JsonObject state = object(data.get("plan_state"));
      if (state != null) latestState = state;
      if ("plan_task".equals(string(data.get("operation")))) latestPlan = data;
    }
    if (latestPlan == null) return "";
    JsonObject normalized = object(latestPlan.get("normalized_plan"));
    if (normalized == null) return "";
    JsonObject summary = new JsonObject();
    copyJson(normalized, summary,
        "goal", "quality_mode", "writing_mode", "planned_files", "verification_plan",
        "interaction_required", "interaction_checks", "waived_checks",
        "interaction_check_warnings");
    if (latestState != null) summary.add("plan_state", latestState.deepCopy());
    String value = GSON.toJson(summary);
    return truncate(value, 6000);
  }

  private static Projection create(
      AgentToolCall call,
      AgentMessage resultMessage,
      Set<String> staleReadPaths,
      boolean keepBrowserFailureDetails) {
    if (call == null || call.arguments() == null || resultMessage == null) return null;
    if (isReadTool(call.name())) return createRead(call, resultMessage, staleReadPaths);
    if ("browser_test".equals(call.name())) {
      return createBrowser(call, resultMessage, keepBrowserFailureDetails);
    }
    if (!isWriteTool(call.name())) return null;
    String rawArguments = GSON.toJson(call.arguments().asMap());
    if (rawArguments.length() <= LARGE_WRITE_ARGUMENT_CHARS) return null;
    JsonObject result = parseObject(resultMessage.content());
    if (result == null) return null;
    JsonObject data = object(result.get("data"));
    boolean repair = requiresRepairProjection(result, data);
    if (!repair && !successfulWithoutPartialFailure(result, data)) return null;

    Map<String, Object> source = call.arguments().asMap();
    PayloadStats stats = payloadStats(call.name(), source);
    Map<String, Object> compactArguments = compactArguments(call.name(), source);
    compactArguments.put(
        "request_projection", repair ? "write_repair_compacted" : "successful_write_compacted");
    compactArguments.put("original_arguments_chars", rawArguments.length());
    compactArguments.put("payload_chars", stats.chars);
    compactArguments.put("payload_sha256", stats.sha256);
    Set<Integer> failedIndexes = failedIndexes(data);
    Set<Integer> appliedIndexes = completedIndexes(data);
    if (repair) {
      addRetryAnchors(
          compactArguments, call.name(), source, failedIndexes, appliedIndexes);
    }

    JsonObject compactResult = new JsonObject();
    String status = string(result.get("status")).toLowerCase(Locale.US);
    compactResult.addProperty("status", status.isEmpty() ? (repair ? "error" : "success") : status);
    if (result.has("error_code")) compactResult.add("error_code", result.get("error_code").deepCopy());
    if (result.has("message")) {
      compactResult.addProperty(
          "message", truncate(string(result.get("message")), MAX_ERROR_MESSAGE_CHARS));
    }
    JsonObject compactData = new JsonObject();
    copyJson(data, compactData, "path", "resolved_path", "requested_path", "file_role");
    addFallback(compactData, "path", first(source, "path", "requested_path", "resolved_path"));
    addFallback(compactData, "file_role", first(source, "file_role", "role"));
    copyJson(
        data,
        compactData,
        "operation",
        "changed",
        "current_file_changed",
        "no_change",
        "created",
        "overwritten",
        "conflict_resolution",
        "count",
        "requested_count",
        "applied_count",
        "failed_count",
        "no_change_count",
        "total_lines",
        "content_hash",
        "artifact_manifest",
        "plan_state");
    if (repair) {
      copyJson(
          data,
          compactData,
          "partial_apply",
          "partial_failure_code",
          "next_action_must_only_fix_skipped");
      compactData.addProperty("repair_evidence_compacted", true);
      addIntegerList(compactData, "failed_indexes", failedIndexes);
      addIntegerList(compactData, "applied_indexes", integerValues(data, "applied_indexes"));
      List<String> windows = candidateWindows(data);
      if (!windows.isEmpty()) compactData.add("candidate_windows", GSON.toJsonTree(windows));
    }
    compactData.addProperty("original_arguments_chars", rawArguments.length());
    compactData.addProperty("payload_chars", stats.chars);
    compactData.addProperty("payload_sha256", stats.sha256);
    compactResult.add("data", compactData);
    compactResult.addProperty("retryable", booleanValue(result.get("retryable")));
    return new Projection(new ToolArguments(compactArguments), GSON.toJson(compactResult));
  }

  private static Projection createRead(
      AgentToolCall call, AgentMessage resultMessage, Set<String> staleReadPaths) {
    JsonObject result = parseObject(resultMessage.content());
    if (result == null || !"success".equalsIgnoreCase(string(result.get("status")))) return null;
    JsonObject data = object(result.get("data"));
    if (data == null || GSON.toJson(data).length() < 2048) return null;
    JsonObject compactData = data.deepCopy();
    removeDuplicateLines(compactData);
    boolean stale = compactStaleReadContent(compactData, staleReadPaths);
    JsonObject compactResult = result.deepCopy();
    compactResult.add("data", compactData);
    Map<String, Object> arguments = new LinkedHashMap<>(call.arguments().asMap());
    arguments.put("request_projection", stale
        ? "stale_read_compacted" : "read_content_deduplicated");
    return new Projection(new ToolArguments(arguments), GSON.toJson(compactResult));
  }

  private static void removeDuplicateLines(JsonObject data) {
    if (data.has("content") && data.has("lines")) data.remove("lines");
    JsonElement items = data.get("items");
    if (items == null || !items.isJsonArray()) return;
    for (JsonElement raw : items.getAsJsonArray()) {
      JsonObject item = object(raw);
      if (item == null) continue;
      JsonObject result = object(item.get("result"));
      if (result != null && result.has("content") && result.has("lines")) result.remove("lines");
    }
  }

  private static boolean compactStaleReadContent(
      JsonObject data, Set<String> staleReadPaths) {
    if (data == null || staleReadPaths == null || staleReadPaths.isEmpty()) return false;
    boolean changed = compactStaleReadItem(data, staleReadPaths, "");
    JsonElement items = data.get("items");
    if (items != null && items.isJsonArray()) for (JsonElement raw : items.getAsJsonArray()) {
      JsonObject item = object(raw);
      JsonObject nested = item == null ? null : object(item.get("result"));
      if (nested != null) changed |= compactStaleReadItem(nested, staleReadPaths, "");
    }
    return changed;
  }

  private static boolean compactStaleReadItem(
      JsonObject data, Set<String> staleReadPaths, String fallbackPath) {
    String path = firstJson(data, "resolved_path", "path");
    if (path.isEmpty()) path = fallbackPath;
    if (!staleReadPaths.contains(normalizePath(path))) return false;
    JsonElement content = data.get("content");
    if (content != null && content.isJsonPrimitive()) {
      String value = string(content);
      data.addProperty("content_chars", value.length());
      if (!data.has("content_hash")) data.addProperty("content_hash", sha256(value));
    }
    data.remove("content");
    data.remove("lines");
    data.addProperty("content_compacted", true);
    data.addProperty("compaction_reason", "superseded_by_new_file_revision");
    return true;
  }

  private static int latestBrowserFailureIndex(List<AgentMessage> messages) {
    int latest = -1;
    for (int index = 0; index < messages.size(); index++) {
      AgentMessage message = messages.get(index);
      if (message.role() != AgentMessage.Role.ASSISTANT) continue;
      Map<String, AgentMessage> results = new LinkedHashMap<>();
      int cursor = index + 1;
      while (cursor < messages.size() && messages.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage result = messages.get(cursor++);
        results.put(result.toolCallId(), result);
      }
      for (AgentToolCall call : message.toolCalls()) {
        if (!"browser_test".equals(call.name())) continue;
        AgentMessage resultMessage = results.get(call.id());
        JsonObject root = parseObject(resultMessage == null ? "" : resultMessage.content());
        JsonObject data = root == null ? null : object(root.get("data"));
        if (data != null && !booleanValue(data.get("passed"))) latest = index;
      }
      index = Math.max(index, cursor - 1);
    }
    return latest;
  }

  private static Map<String, Integer> latestSuccessfulWriteIndexes(List<AgentMessage> messages) {
    Map<String, Integer> latest = new LinkedHashMap<>();
    for (int index = 0; index < messages.size(); index++) {
      AgentMessage message = messages.get(index);
      if (message.role() != AgentMessage.Role.ASSISTANT) continue;
      Map<String, AgentMessage> results = new LinkedHashMap<>();
      int cursor = index + 1;
      while (cursor < messages.size() && messages.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage result = messages.get(cursor++);
        results.put(result.toolCallId(), result);
      }
      for (AgentToolCall call : message.toolCalls()) {
        if (!isWriteTool(call.name())) continue;
        JsonObject root = parseObject(results.containsKey(call.id())
            ? results.get(call.id()).content() : "");
        JsonObject data = root == null ? null : object(root.get("data"));
        if (root == null || !successfulWithoutPartialFailure(root, data)) continue;
        addWritePath(latest, value(first(call.arguments().asMap(), "path", "requested_path")), index);
        addWritePath(latest, firstJson(data, "resolved_path", "path", "requested_path"), index);
      }
      index = Math.max(index, cursor - 1);
    }
    return latest;
  }

  private static Set<String> staleReadPaths(
      AgentToolCall call, Map<String, Integer> latestWrites, int readIndex) {
    Set<String> out = new LinkedHashSet<>();
    if (call == null || !isReadTool(call.name())) return out;
    collectReadArgumentPaths(call.arguments().asMap(), out);
    out.removeIf(path -> latestWrites.getOrDefault(path, -1) <= readIndex);
    return out;
  }

  private static void collectReadArgumentPaths(Map<String, Object> source, Set<String> out) {
    addReadPath(out, value(first(source, "path", "resolved_path")));
    Object targets = source.get("targets");
    if (targets instanceof List) for (Object raw : (List<?>) targets) {
      if (!(raw instanceof Map)) continue;
      addReadPath(out, value(firstAny((Map<?, ?>) raw, "path", "resolved_path")));
    }
  }

  private static void addWritePath(Map<String, Integer> target, String path, int index) {
    String normalized = normalizePath(path);
    if (!normalized.isEmpty()) target.put(normalized, index);
  }

  private static void addReadPath(Set<String> target, String path) {
    String normalized = normalizePath(path);
    if (!normalized.isEmpty()) target.add(normalized);
  }

  private static String normalizePath(String value) {
    return value == null ? "" : value.trim().replace('\\', '/');
  }

  private static String firstJson(JsonObject source, String... keys) {
    if (source == null) return "";
    for (String key : keys) {
      String value = string(source.get(key));
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  private static Object firstAny(Map<?, ?> source, String... keys) {
    if (source == null) return null;
    for (String key : keys) {
      Object value = source.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
    }
    return null;
  }

  private static Projection createBrowser(
      AgentToolCall call, AgentMessage resultMessage, boolean keepFailureDetails) {
    JsonObject result = parseObject(resultMessage.content());
    if (result == null) return null;
    JsonObject data = object(result.get("data"));
    if (data == null) return null;
    boolean passed = booleanValue(data.get("passed"));
    Map<String, Object> arguments = new LinkedHashMap<>();
    Map<String, Object> source = call.arguments().asMap();
    copyFirst(source, arguments, "operation", "operation");
    copyFirst(source, arguments, "entry_path", "entry_path");
    copyFirst(source, arguments, "target_url", "target_url");
    Object ids = source.get("interaction_check_ids");
    if (ids != null) arguments.put("interaction_check_ids", ids);
    arguments.put("step_count", listSize(source.get("steps")));
    arguments.put("request_projection", passed ? "browser_success_compacted"
        : keepFailureDetails ? "browser_failure_compacted" : "browser_failure_superseded");
    if (!passed && keepFailureDetails) addFailedBrowserStep(arguments, source, data);

    JsonObject compact = new JsonObject();
    String status = string(result.get("status"));
    compact.addProperty("status", status.isEmpty() ? "success" : status);
    if (result.has("error_code")) compact.add("error_code", result.get("error_code").deepCopy());
    if (result.has("message")) compact.addProperty(
        "message", truncate(string(result.get("message")), MAX_ERROR_MESSAGE_CHARS));
    JsonObject compactData = new JsonObject();
    if (!passed && !keepFailureDetails) {
      copyJson(data, compactData,
          "operation", "browser_operation", "status", "passed", "failure_class",
          "failed_check_id");
    } else {
      copyJson(data, compactData,
          "operation", "browser_operation", "entry_path", "status", "passed", "title",
          "steps_passed", "failure_reason", "failure_class", "failed_check_id",
          "expected_check_ids", "covered_check_ids", "missing_check_ids", "next_action",
          "selector_candidates", "test_manifest", "interaction_audit", "plan_state");
      copyNonEmpty(data, compactData, "page_errors", "console_logs");
    }
    JsonObject layout = keepFailureDetails || passed ? object(data.get("layout_audit")) : null;
    if (layout != null) {
      JsonObject layoutSummary = new JsonObject();
      copyJson(layout, layoutSummary,
          "applicable", "passed", "blocking_issues", "warnings", "screenshot_path");
      compactData.add("layout_audit", layoutSummary);
    }
    compact.add("data", compactData);
    compact.addProperty("retryable", booleanValue(result.get("retryable")));
    return new Projection(new ToolArguments(arguments), GSON.toJson(compact));
  }

  private static void addFailedBrowserStep(
      Map<String, Object> target, Map<String, Object> source, JsonObject data) {
    JsonObject failure = object(data.get("step_failure"));
    if (failure == null) return;
    int index = count(failure, "index");
    Object raw = source.get("steps");
    if (!(raw instanceof List) || index <= 0 || index > ((List<?>) raw).size()) return;
    Object step = ((List<?>) raw).get(index - 1);
    if (step != null) target.put("failed_step", step);
  }

  private static void copyNonEmpty(JsonObject source, JsonObject target, String... keys) {
    for (String key : keys) {
      JsonElement value = source.get(key);
      if (nonEmpty(value)) target.add(key, value.deepCopy());
    }
  }

  private static Map<String, Object> compactArguments(
      String tool, Map<String, Object> source) {
    Map<String, Object> compact = new LinkedHashMap<>();
    copyFirst(source, compact, "path", "path", "requested_path", "resolved_path");
    copyFirst(source, compact, "file_role", "file_role", "role");
    if ("search_replace".equals(tool)) {
      int count = listSize(source.get("replacements"));
      compact.put("replacement_count", count > 0 ? count : source.containsKey("new") ? 1 : 0);
    } else if ("rewrite".equals(tool)) {
      int count = listSize(source.get("units"));
      compact.put("unit_count", count > 0 ? count : source.containsKey("content") ? 1 : 0);
    } else if ("create_file".equals(tool)) {
      compact.put("content_chars", stringLength(source.get("content")));
    }
    return compact;
  }

  private static boolean successfulWithoutPartialFailure(JsonObject result, JsonObject data) {
    if (!"success".equalsIgnoreCase(string(result.get("status")))) return false;
    if (booleanValue(result.get("retryable"))) return false;
    return !hasPartialFailure(data);
  }

  private static boolean requiresRepairProjection(JsonObject result, JsonObject data) {
    String status = string(result.get("status")).toLowerCase(Locale.US);
    return "error".equals(status)
        || "failed".equals(status)
        || "failure".equals(status)
        || booleanValue(result.get("retryable"))
        || hasPartialFailure(data);
  }

  private static boolean hasPartialFailure(JsonObject data) {
    if (data == null) return false;
    if (positive(data.get("failed_count")) || booleanValue(data.get("partial_apply"))) return true;
    int requested = count(data, "requested_count", "count");
    int applied = count(data, "applied_count", "success_count");
    int noChange = Math.max(0, count(data, "no_change_count", "unchanged_count"));
    if (requested >= 0 && applied >= 0 && requested > applied + noChange) return true;
    if (nonEmpty(data.get("skipped_indexes"))
        || nonEmpty(data.get("skipped_replacements"))
        || nonEmpty(data.get("failed_indexes"))
        || nonEmpty(data.get("failed_replacements"))
        || nonEmpty(data.get("partial_failure_code"))
        || nonEmpty(data.get("failures"))
        || nonEmpty(data.get("errors"))) return true;
    JsonElement results = data.get("results");
    if (results != null && results.isJsonArray()) {
      for (JsonElement item : results.getAsJsonArray()) {
        JsonObject object = object(item);
        if (object == null) continue;
        String status = string(object.get("status")).toLowerCase(Locale.US);
        if ("error".equals(status)
            || "failed".equals(status)
            || "skipped".equals(status)
            || "conflict".equals(status)) return true;
      }
    }
    return false;
  }

  private static PayloadStats payloadStats(String tool, Map<String, Object> arguments) {
    List<String> payloads = new ArrayList<>();
    if ("create_file".equals(tool)) {
      addString(payloads, arguments.get("content"));
    } else if ("search_replace".equals(tool)) {
      addString(payloads, arguments.get("old"));
      addString(payloads, arguments.get("new"));
      collectFields(payloads, arguments.get("replacements"), "old", "new");
    } else if ("rewrite".equals(tool)) {
      addString(payloads, arguments.get("content"));
      collectFields(payloads, arguments.get("units"), "content");
    }
    StringBuilder joined = new StringBuilder();
    int chars = 0;
    for (String payload : payloads) {
      chars += payload.length();
      joined.append(payload.length()).append(':').append(payload).append(';');
    }
    return new PayloadStats(chars, sha256(joined.toString()));
  }

  private static void addRetryAnchors(
      Map<String, Object> compact,
      String tool,
      Map<String, Object> source,
      Set<Integer> failedIndexes,
      Set<Integer> appliedIndexes) {
    List<RepairUnit> units = repairUnits(tool, source, failedIndexes, appliedIndexes);
    long encodedChars = 0L;
    int payloadChars = 0;
    StringBuilder payloadDigest = new StringBuilder();
    for (RepairUnit unit : units) {
      payloadChars += unit.payload.length();
      encodedChars += encodedChars(unit.payload);
      payloadDigest.append(unit.payload.length()).append(':').append(unit.payload).append(';');
    }
    boolean keepExact = encodedChars <= MAX_REPAIR_PAYLOAD_CHARS;
    List<Map<String, Object>> anchors = new ArrayList<>();
    for (RepairUnit unit : units) {
      if (anchors.size() >= MAX_RETRY_ITEMS) break;
      Map<String, Object> anchor = new LinkedHashMap<>();
      anchor.put("index", unit.index);
      if ("search_replace".equals(tool)) addSearchAnchor(anchor, unit.source);
      else if ("rewrite".equals(tool)) {
        copyScalar(unit.source, anchor, "kind", 120);
        copyScalar(unit.source, anchor, "name", 240);
      }
      addRepairPayload(anchor, unit.payloadKey, unit.payload, keepExact);
      anchors.add(anchor);
    }
    if (!failedIndexes.isEmpty()) compact.put("failed_indexes", new ArrayList<>(failedIndexes));
    compact.put("repair_payload_chars", payloadChars);
    compact.put("repair_payload_encoded_chars", encodedChars);
    compact.put("repair_payload_sha256", sha256(payloadDigest.toString()));
    compact.put("repair_payload_truncated", !keepExact);
    if (units.size() > anchors.size()) compact.put("retry_units_omitted", units.size() - anchors.size());
    if (!anchors.isEmpty()) compact.put("retry_anchors", anchors);
  }

  private static List<RepairUnit> repairUnits(
      String tool,
      Map<String, Object> source,
      Set<Integer> failedIndexes,
      Set<Integer> appliedIndexes) {
    List<RepairUnit> output = new ArrayList<>();
    if ("create_file".equals(tool)) {
      output.add(new RepairUnit(0, source, "content", value(source.get("content"))));
      return output;
    }
    String collectionKey = "search_replace".equals(tool) ? "replacements" : "units";
    String payloadKey = "search_replace".equals(tool) ? "new" : "content";
    Object raw = source.get(collectionKey);
    if (raw instanceof List) {
      List<?> values = (List<?>) raw;
      for (int index = 0; index < values.size(); index++) {
        if (!failedIndexes.isEmpty() && !failedIndexes.contains(index)) continue;
        if (failedIndexes.isEmpty() && appliedIndexes.contains(index)) continue;
        Object item = values.get(index);
        if (!(item instanceof Map)) continue;
        Map<?, ?> unit = (Map<?, ?>) item;
        output.add(new RepairUnit(index, unit, payloadKey, value(unit.get(payloadKey))));
      }
    } else if ((failedIndexes.isEmpty() || failedIndexes.contains(0))
        && !appliedIndexes.contains(0)) {
      output.add(new RepairUnit(0, source, payloadKey, value(source.get(payloadKey))));
    }
    return output;
  }

  private static void addSearchAnchor(Map<String, Object> anchor, Map<?, ?> replacement) {
    String old = value(replacement.get("old"));
    if (!old.isEmpty()) {
      anchor.put("old", truncate(old, MAX_RETRY_TEXT_CHARS));
      anchor.put("old_chars", old.length());
      anchor.put("old_sha256", sha256(old));
      if (old.length() > MAX_RETRY_TEXT_CHARS) anchor.put("old_truncated", true);
    }
    Object expected = replacement.get("expected_matches");
    if (expected != null) anchor.put("expected_matches", expected);
    copyScalar(replacement, anchor, "summary", 240);
  }

  private static void addRepairPayload(
      Map<String, Object> target, String key, String payload, boolean keepExact) {
    target.put(key + "_chars", payload.length());
    target.put(key + "_sha256", sha256(payload));
    if (keepExact) {
      target.put(key, payload);
      target.put(key + "_truncated", false);
      return;
    }
    target.put(key + "_truncated", true);
    target.put(
        key + "_head_preview",
        payload.substring(0, Math.min(MAX_REPAIR_PREVIEW_CHARS, payload.length())));
    target.put(
        key + "_tail_preview",
        payload.substring(Math.max(0, payload.length() - MAX_REPAIR_PREVIEW_CHARS)));
  }

  private static int encodedChars(String value) {
    String encoded = GSON.toJson(value == null ? "" : value);
    return Math.max(0, encoded.length() - 2);
  }

  private static Set<Integer> failedIndexes(JsonObject data) {
    Set<Integer> output = new LinkedHashSet<>();
    output.addAll(integerValues(data, "failed_indexes"));
    output.addAll(integerValues(data, "skipped_indexes"));
    if (data == null) return output;
    for (String key : new String[] {"skipped_replacements", "precheck_results", "results"}) {
      JsonElement raw = data.get(key);
      if (raw == null || !raw.isJsonArray()) continue;
      for (JsonElement item : raw.getAsJsonArray()) {
        JsonObject object = object(item);
        if (object == null || !object.has("index")) continue;
        String status = string(object.get("status")).toLowerCase(Locale.US);
        if ("skipped_replacements".equals(key)
            || "error".equals(status)
            || "failed".equals(status)
            || "skipped".equals(status)
            || "conflict".equals(status)) addInteger(output, object.get("index"));
      }
    }
    int requested = count(data, "requested_count", "count");
    int applied = Math.max(0, count(data, "applied_count", "success_count"));
    int noChange = Math.max(0, count(data, "no_change_count", "unchanged_count"));
    int expectedFailures = requested < 0 ? 0 : Math.max(0, requested - applied - noChange);
    if (expectedFailures > output.size()) {
      Set<Integer> completed = completedIndexes(data);
      if (completed.isEmpty()) {
        for (int index = 0; index < applied + noChange && index < requested; index++) {
          completed.add(index);
        }
      }
      for (int index = 0;
          index < requested && output.size() < expectedFailures && output.size() < 32;
          index++) {
        if (!completed.contains(index)) output.add(index);
      }
    }
    return output;
  }

  private static Set<Integer> completedIndexes(JsonObject data) {
    Set<Integer> output = new LinkedHashSet<>();
    output.addAll(integerValues(data, "applied_indexes"));
    if (data == null) return output;
    JsonElement results = data.get("results");
    if (results != null && results.isJsonArray()) {
      for (JsonElement item : results.getAsJsonArray()) {
        JsonObject object = object(item);
        if (object == null || !object.has("index")) continue;
        String status = string(object.get("status")).toLowerCase(Locale.US);
        if ("success".equals(status)
            || "applied".equals(status)
            || "no_change".equals(status)
            || "unchanged".equals(status)) addInteger(output, object.get("index"));
      }
    }
    if (output.isEmpty()) {
      int requested = count(data, "requested_count", "count");
      int applied = Math.max(0, count(data, "applied_count", "success_count"));
      int noChange = Math.max(0, count(data, "no_change_count", "unchanged_count"));
      for (int index = 0; index < applied + noChange && index < requested; index++) {
        output.add(index);
      }
    }
    return output;
  }

  private static int count(JsonObject data, String... keys) {
    if (data == null) return -1;
    for (String key : keys) {
      JsonElement value = data.get(key);
      if (value == null || value.isJsonNull()) continue;
      try {
        return value.getAsInt();
      } catch (Exception ignored) {
        // Try the next compatible field name.
      }
    }
    return -1;
  }

  private static Set<Integer> integerValues(JsonObject data, String key) {
    Set<Integer> output = new LinkedHashSet<>();
    if (data == null) return output;
    JsonElement raw = data.get(key);
    if (raw == null || raw.isJsonNull()) return output;
    if (raw.isJsonArray()) {
      for (JsonElement item : raw.getAsJsonArray()) addInteger(output, item);
    } else {
      addInteger(output, raw);
    }
    return output;
  }

  private static void addInteger(Set<Integer> output, JsonElement value) {
    try {
      int number = value.getAsInt();
      if (number >= 0 && output.size() < 32) output.add(number);
    } catch (Exception ignored) {
      // Malformed indexes remain represented by failed_count/error text.
    }
  }

  private static void addIntegerList(JsonObject target, String key, Set<Integer> values) {
    if (values == null || values.isEmpty()) return;
    target.add(key, GSON.toJsonTree(new ArrayList<>(values)));
  }

  private static List<String> candidateWindows(JsonObject data) {
    if (data == null) return Collections.emptyList();
    Set<String> values = new LinkedHashSet<>();
    for (String key :
        new String[] {
          "candidate_window",
          "current_window",
          "current_excerpt",
          "old_preview",
          "suggested_old",
          "closest_match",
          "candidates",
          "candidate_windows",
          "skipped_replacements",
          "precheck_results",
          "results",
          "failures",
          "errors"
        }) {
      collectCandidate(key, data.get(key), values, 0);
      if (values.size() >= MAX_RETRY_ITEMS) break;
    }
    return new ArrayList<>(values);
  }

  private static void collectCandidate(
      String label, JsonElement raw, Set<String> output, int depth) {
    if (raw == null || raw.isJsonNull() || output.size() >= MAX_RETRY_ITEMS || depth > 3) return;
    if (raw.isJsonPrimitive()) {
      String value = string(raw).trim();
      if (!value.isEmpty()) {
        output.add(truncate(label + "=" + value, MAX_RETRY_TEXT_CHARS));
      }
      return;
    }
    if (raw.isJsonArray()) {
      int count = 0;
      for (JsonElement item : raw.getAsJsonArray()) {
        if (count++ >= MAX_RETRY_ITEMS || output.size() >= MAX_RETRY_ITEMS) break;
        collectCandidate(label, item, output, depth + 1);
      }
      return;
    }
    JsonObject object = object(raw);
    if (object == null) return;
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      if (!candidateKey(entry.getKey())) continue;
      collectCandidate(entry.getKey(), entry.getValue(), output, depth + 1);
      if (output.size() >= MAX_RETRY_ITEMS) break;
    }
  }

  private static boolean candidateKey(String key) {
    String value = key == null ? "" : key.toLowerCase(Locale.US);
    if (value.equals("new") || value.equals("content") || value.startsWith("new_")) return false;
    return value.equals("old")
        || value.contains("anchor")
        || value.contains("candidate")
        || value.contains("current")
        || value.contains("excerpt")
        || value.contains("snippet")
        || value.contains("preview")
        || value.equals("text")
        || value.equals("line")
        || value.equals("start_line")
        || value.equals("end_line")
        || value.equals("message")
        || value.equals("error")
        || value.equals("error_code");
  }

  private static void collectFields(List<String> out, Object raw, String... fields) {
    if (!(raw instanceof List)) return;
    for (Object item : (List<?>) raw) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> map = (Map<?, ?>) item;
      for (String field : fields) addString(out, map.get(field));
    }
  }

  private static void addString(List<String> out, Object value) {
    if (value != null) out.add(String.valueOf(value));
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte item : bytes) hex.append(String.format(Locale.US, "%02x", item & 0xff));
      return hex.toString();
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static boolean isWriteTool(String name) {
    return "create_file".equals(name) || "search_replace".equals(name) || "rewrite".equals(name);
  }

  private static boolean isReadTool(String name) {
    return "read_file".equals(name) || "read_file_batch".equals(name) || "read_plan".equals(name);
  }

  private static JsonObject parseObject(String value) {
    try {
      JsonElement parsed = JsonParser.parseString(value == null ? "" : value);
      return object(parsed);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static JsonObject object(JsonElement value) {
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static String string(JsonElement value) {
    try {
      return value == null || value.isJsonNull() ? "" : value.getAsString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static boolean booleanValue(JsonElement value) {
    try {
      return value != null && !value.isJsonNull() && value.getAsBoolean();
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean positive(JsonElement value) {
    try {
      return value != null && !value.isJsonNull() && value.getAsDouble() > 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean nonEmpty(JsonElement value) {
    if (value == null || value.isJsonNull()) return false;
    if (value.isJsonArray()) return value.getAsJsonArray().size() > 0;
    if (value.isJsonObject()) return value.getAsJsonObject().size() > 0;
    return !string(value).trim().isEmpty();
  }

  private static int listSize(Object value) {
    return value instanceof List ? ((List<?>) value).size() : 0;
  }

  private static int stringLength(Object value) {
    return value == null ? 0 : String.valueOf(value).length();
  }

  private static String value(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Object first(Map<String, Object> source, String... keys) {
    for (String key : keys) {
      Object value = source.get(key);
      if (value != null && !String.valueOf(value).isEmpty()) return value;
    }
    return null;
  }

  private static void addFallback(JsonObject target, String key, Object value) {
    if (!target.has(key) && value != null && !String.valueOf(value).isEmpty()) {
      target.add(key, GSON.toJsonTree(value));
    }
  }

  private static void copyScalar(
      Map<?, ?> source, Map<String, Object> target, String key, int maxChars) {
    Object value = source.get(key);
    if (value == null) return;
    target.put(key, truncate(String.valueOf(value), maxChars));
  }

  private static String truncate(String value, int maxChars) {
    String safe = value == null ? "" : value;
    if (safe.length() <= maxChars) return safe;
    return safe.substring(0, Math.max(0, maxChars - 1)) + "…";
  }

  private static void copyFirst(
      Map<String, Object> source, Map<String, Object> target, String targetKey, String... sourceKeys) {
    for (String key : sourceKeys) {
      Object value = source.get(key);
      if (value == null || String.valueOf(value).isEmpty()) continue;
      target.put(targetKey, value);
      return;
    }
  }

  private static void copyJson(JsonObject source, JsonObject target, String... keys) {
    if (source == null) return;
    for (String key : keys) {
      JsonElement value = source.get(key);
      if (value != null && !value.isJsonNull()) target.add(key, value.deepCopy());
    }
  }

  private static final class Projection {
    final ToolArguments arguments;
    final String resultJson;

    Projection(ToolArguments arguments, String resultJson) {
      this.arguments = arguments;
      this.resultJson = resultJson;
    }
  }

  private static final class PayloadStats {
    final int chars;
    final String sha256;

    PayloadStats(int chars, String sha256) {
      this.chars = chars;
      this.sha256 = sha256;
    }
  }

  private static final class RepairUnit {
    final int index;
    final Map<?, ?> source;
    final String payloadKey;
    final String payload;

    RepairUnit(int index, Map<?, ?> source, String payloadKey, String payload) {
      this.index = index;
      this.source = source;
      this.payloadKey = payloadKey;
      this.payload = payload;
    }
  }
}
