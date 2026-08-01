package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.ToolArguments;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
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

/** Builds the bounded request view of completed large writes and goal-driven evidence reads. */
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
    Set<String> preservedWriteCalls = preservedWriteCalls(safe);
    Set<String> preservedFullReadCalls = preservedFullReadCalls(safe);
    Set<String> preservedReadPlanCalls = preservedReadPlanCalls(safe);
    List<AgentMessage> out = new ArrayList<>(safe.size());
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
            preservedWriteCalls.contains(call.id()),
            preservedFullReadCalls.contains(call.id()),
            preservedReadPlanCalls.contains(call.id()));
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

  private static Projection create(
      AgentToolCall call,
      AgentMessage resultMessage,
      boolean preserveWritePayload,
      boolean preserveFullRead,
      boolean preserveReadPlanEvidence) {
    if (call == null || call.arguments() == null || resultMessage == null) return null;
    if ("read_file".equals(call.name())) {
      return preserveFullRead ? null : compactStaleFullRead(call, resultMessage);
    }
    if ("read_plan".equals(call.name())) {
      return compactReadPlan(call, resultMessage, preserveReadPlanEvidence);
    }
    if ("browser_test".equals(call.name())) return compactBrowserTest(call, resultMessage);
    if (!isWriteTool(call.name())) return null;
    String rawArguments = GSON.toJson(call.arguments().asMap());
    if (preserveWritePayload && rawArguments.length() <= MAX_REPAIR_PAYLOAD_CHARS) return null;
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
    boolean atomicSearchReplaceFailure = "search_replace".equals(call.name())
        && "error".equalsIgnoreCase(string(result.get("status")))
        && data != null
        && data.has("failures")
        && data.get("failures").isJsonArray();
    Set<Integer> failedIndexes = atomicSearchReplaceFailure
        ? Collections.emptySet() : failedIndexes(data);
    Set<Integer> appliedIndexes = completedIndexes(data);
    if (repair) {
      boolean compactRiskPayload = "search_replace".equals(call.name())
          && string(result.get("error_code")).contains("destructive_change");
      addRetryAnchors(
          compactArguments,
          call.name(),
          source,
          failedIndexes,
          appliedIndexes,
          compactRiskPayload);
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
        "plan_state");
    if ("search_replace".equals(call.name())) {
      copySearchReplaceRiskEvidence(data, compactData);
    }
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
      boolean structuredSearchRepair = "search_replace".equals(call.name())
          && copySearchReplaceRepairEvidence(data, compactData);
      if (!structuredSearchRepair) {
        List<String> windows = candidateWindows(data);
        if (!windows.isEmpty()) compactData.add("candidate_windows", GSON.toJsonTree(windows));
      }
    }
    compactData.addProperty("original_arguments_chars", rawArguments.length());
    compactData.addProperty("payload_chars", stats.chars);
    compactData.addProperty("payload_sha256", stats.sha256);
    compactResult.add("data", compactData);
    compactResult.addProperty("retryable", booleanValue(result.get("retryable")));
    return new Projection(new ToolArguments(compactArguments), GSON.toJson(compactResult));
  }

  private static boolean copySearchReplaceRepairEvidence(JsonObject source, JsonObject target) {
    if (source == null || target == null) return false;
    JsonElement rawFailures = source.get("failures");
    if (rawFailures == null || !rawFailures.isJsonArray()) return false;
    copyJson(
        source,
        target,
        "recommended_next_action",
        "expected_next_action");
    JsonObject recommendedRetry = compactSearchReplaceCandidate(source.get("recommended_retry"));
    if (recommendedRetry != null) target.add("recommended_retry", recommendedRetry);
    JsonArray failures = new JsonArray();
    int remainingCandidates = MAX_RETRY_ITEMS;
    for (JsonElement rawFailure : rawFailures.getAsJsonArray()) {
      JsonObject failure = object(rawFailure);
      if (failure == null) continue;
      JsonObject summary = new JsonObject();
      copyJson(
          failure,
          summary,
          "index",
          "failed_index",
          "status",
          "error_code",
          "actual_matches",
          "original_matches",
          "matched_lines",
          "old_line_count",
          "old_char_count",
          "maximum_old_line_count",
          "maximum_old_char_count",
          "too_large_old",
          "risk_level",
          "risk_reasons",
          "deletion_risk",
          "scope_coverage_ratio",
          "retained_ratio",
          "function_definition_count",
          "suggested_strategy",
          "conflict_type",
          "conflict_with_index");
      if (failure.has("error_message")) {
        summary.addProperty(
            "error_message", truncate(string(failure.get("error_message")), MAX_ERROR_MESSAGE_CHARS));
      }
      JsonElement rawCandidates = failure.get("candidate_windows");
      if (remainingCandidates > 0 && rawCandidates != null && rawCandidates.isJsonArray()) {
        JsonArray candidates = new JsonArray();
        for (JsonElement rawCandidate : rawCandidates.getAsJsonArray()) {
          if (remainingCandidates <= 0) break;
          JsonObject candidate = compactSearchReplaceCandidate(rawCandidate);
          if (candidate == null) continue;
          candidates.add(candidate);
          remainingCandidates--;
        }
        if (candidates.size() > 0) summary.add("candidate_windows", candidates);
      }
      failures.add(summary);
    }
    target.add("failures", failures);
    copyBoundedSearchReplaceCandidates(source, target, "copyable_old_candidates");
    copyBoundedSearchReplaceCandidates(source, target, "preferred_retry_old");
    return true;
  }

  private static void copySearchReplaceRiskEvidence(JsonObject source, JsonObject target) {
    if (source == null || target == null) return;
    copyJson(
        source,
        target,
        "too_large_old",
        "risk_level",
        "risk_reasons",
        "deletion_risk",
        "scope_coverage_ratio",
        "retained_ratio",
        "high_risk_replacement_indexes",
        "requires_verification");
    JsonElement rawResults = source.get("results");
    if (rawResults == null || !rawResults.isJsonArray()) return;
    JsonArray summaries = new JsonArray();
    for (JsonElement rawResult : rawResults.getAsJsonArray()) {
      JsonObject result = object(rawResult);
      if (result == null) continue;
      JsonObject summary = new JsonObject();
      copyJson(
          result,
          summary,
          "index",
          "status",
          "too_large_old",
          "risk_level",
          "risk_reasons",
          "deletion_risk",
          "scope_coverage_ratio",
          "retained_ratio",
          "function_definition_count");
      if (summary.size() > 0) summaries.add(summary);
    }
    if (summaries.size() > 0) target.add("results", summaries);
  }

  private static void copyBoundedSearchReplaceCandidates(
      JsonObject source, JsonObject target, String key) {
    JsonElement raw = source.get(key);
    if (raw == null || !raw.isJsonArray()) return;
    JsonArray output = new JsonArray();
    for (JsonElement item : raw.getAsJsonArray()) {
      if (output.size() >= MAX_RETRY_ITEMS) break;
      JsonObject candidate = compactSearchReplaceCandidate(item);
      if (candidate != null) output.add(candidate);
    }
    if (output.size() > 0) target.add(key, output);
  }

  private static JsonObject compactSearchReplaceCandidate(JsonElement raw) {
    JsonObject source = object(raw);
    if (source == null) return null;
    JsonObject output = new JsonObject();
    copyJson(
        source,
        output,
        "failed_index",
        "source",
        "start_line",
        "end_line");
    for (String key : new String[] {
        "old", "snippet", "preferred_old", "retry_template", "instruction"
    }) {
      if (!source.has(key)) continue;
      output.addProperty(key, truncate(string(source.get(key)), MAX_RETRY_TEXT_CHARS));
    }
    return output;
  }

  /**
   * Keep exactly the latest successful, bounded write visible until browser verification passes.
   * Earlier writes are compacted, while syntax or product failures retain the latest payload so the
   * model can author selectors and repair anchors without a forced read round.
   */
  private static Set<String> preservedWriteCalls(List<AgentMessage> messages) {
    String latest = "";
    for (int index = 0; index < messages.size(); index++) {
      AgentMessage message = messages.get(index);
      if (message.role() != AgentMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) continue;
      Map<String, AgentMessage> results = new LinkedHashMap<>();
      int cursor = index + 1;
      while (cursor < messages.size() && messages.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage result = messages.get(cursor++);
        results.put(result.toolCallId(), result);
      }
      for (AgentToolCall call : message.toolCalls()) {
        AgentMessage resultMessage = results.get(call.id());
        JsonObject result = resultMessage == null ? null : parseObject(resultMessage.content());
        JsonObject data = result == null ? null : object(result.get("data"));
        if (isWriteTool(call.name())
            && result != null
            && successfulWithoutPartialFailure(result, data)) {
          if (requiresRiskProjection(call.name(), data)) {
            latest = "";
          } else if (GSON.toJson(call.arguments().asMap()).length()
              <= MAX_REPAIR_PAYLOAD_CHARS) {
            latest = call.id();
          }
        } else if ("browser_test".equals(call.name())
            && result != null
            && "success".equalsIgnoreCase(string(result.get("status")))
            && data != null
            && booleanValue(data.get("passed"))) {
          latest = "";
        }
      }
    }
    return latest.isEmpty() ? Collections.emptySet() : Collections.singleton(latest);
  }

  private static boolean requiresRiskProjection(String toolName, JsonObject data) {
    if (!"search_replace".equals(toolName) || data == null) return false;
    String level = string(data.get("risk_level")).toLowerCase(Locale.US);
    return "high".equals(level)
        || "critical".equals(level)
        || booleanValue(data.get("requires_verification"))
        || nonEmpty(data.get("high_risk_replacement_indexes"));
  }

  private static Set<String> preservedFullReadCalls(List<AgentMessage> messages) {
    Map<String, String> latestByPath = new LinkedHashMap<>();
    for (int index = 0; index < messages.size(); index++) {
      AgentMessage message = messages.get(index);
      if (message.role() != AgentMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) continue;
      Map<String, AgentMessage> results = new LinkedHashMap<>();
      int cursor = index + 1;
      while (cursor < messages.size() && messages.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage result = messages.get(cursor++);
        results.put(result.toolCallId(), result);
      }
      for (AgentToolCall call : message.toolCalls()) {
        if (!"read_file".equals(call.name())) continue;
        AgentMessage resultMessage = results.get(call.id());
        JsonObject result = resultMessage == null ? null : parseObject(resultMessage.content());
        JsonObject data = result == null ? null : object(result.get("data"));
        if (result == null
            || !"success".equalsIgnoreCase(string(result.get("status")))
            || data == null
            || !isFullRead(call, data)
            || string(data.get("content")).isEmpty()) continue;
        String path = string(data.get("path"));
        if (path.isEmpty()) path = call.arguments().getString("path", "");
        if (!path.isEmpty()) latestByPath.put(path, call.id());
      }
    }
    return latestByPath.isEmpty()
        ? Collections.emptySet() : new LinkedHashSet<>(latestByPath.values());
  }

  /** Keep one complete evidence packet for each source revision; older packets keep only routing data. */
  private static Set<String> preservedReadPlanCalls(List<AgentMessage> messages) {
    Map<String, String> latestByPathRevision = new LinkedHashMap<>();
    for (int index = 0; index < messages.size(); index++) {
      AgentMessage message = messages.get(index);
      if (message.role() != AgentMessage.Role.ASSISTANT || message.toolCalls().isEmpty()) continue;
      Map<String, AgentMessage> results = new LinkedHashMap<>();
      int cursor = index + 1;
      while (cursor < messages.size() && messages.get(cursor).role() == AgentMessage.Role.TOOL) {
        AgentMessage result = messages.get(cursor++);
        results.put(result.toolCallId(), result);
      }
      for (AgentToolCall call : message.toolCalls()) {
        if (!"read_plan".equals(call.name())) continue;
        AgentMessage resultMessage = results.get(call.id());
        JsonObject result = resultMessage == null ? null : parseObject(resultMessage.content());
        JsonObject data = result == null ? null : object(result.get("data"));
        if (result == null
            || !"success".equalsIgnoreCase(string(result.get("status")))
            || data == null
            || !hasEvidenceContent(data)) continue;
        String path = string(data.get("path"));
        if (path.isEmpty()) path = call.arguments().getString("path", "");
        String revision = string(data.get("revision"));
        if (!path.isEmpty() && !revision.isEmpty()) {
          latestByPathRevision.put(path + "\n" + revision, call.id());
        }
      }
    }
    return latestByPathRevision.isEmpty()
        ? Collections.emptySet()
        : new LinkedHashSet<>(latestByPathRevision.values());
  }

  private static boolean hasEvidenceContent(JsonObject data) {
    JsonElement rawEvidence = data == null ? null : data.get("evidence");
    if (rawEvidence == null || !rawEvidence.isJsonArray()) return false;
    for (JsonElement raw : rawEvidence.getAsJsonArray()) {
      JsonObject evidence = object(raw);
      if (evidence != null && !string(evidence.get("content")).isEmpty()) return true;
    }
    return false;
  }

  private static boolean isFullRead(AgentToolCall call, JsonObject data) {
    if (booleanValue(data.get("full_file")) || "full_file".equals(string(data.get("mode")))) {
      return true;
    }
    Map<String, Object> arguments = call.arguments().asMap();
    return !arguments.containsKey("start_line")
        && !arguments.containsKey("end_line")
        && value(arguments.get("target_function")).isEmpty()
        && value(arguments.get("target_class")).isEmpty()
        && value(arguments.get("target_method")).isEmpty();
  }

  private static Projection compactStaleFullRead(
      AgentToolCall call, AgentMessage resultMessage) {
    JsonObject result = parseObject(resultMessage.content());
    if (result == null) return null;
    JsonObject data = object(result.get("data"));
    if (data == null || !isFullRead(call, data) || string(data.get("content")).isEmpty()) return null;
    JsonObject compactResult = new JsonObject();
    copyJson(result, compactResult, "status", "error_code", "message", "retryable");
    JsonObject compactData = new JsonObject();
    copyJson(data, compactData,
        "operation", "path", "resolved_path", "revision", "mode", "full_file", "total_lines");
    compactData.addProperty("history_projection", "stale_full_read_compacted");
    compactResult.add("data", compactData);
    return new Projection(call.arguments(), GSON.toJson(compactResult));
  }

  private static Projection compactReadPlan(
      AgentToolCall call, AgentMessage resultMessage, boolean preserveEvidence) {
    JsonObject result = parseObject(resultMessage.content());
    if (result == null) return null;
    JsonObject data = object(result.get("data"));
    if (data == null) return null;
    JsonObject compactResult = new JsonObject();
    copyJson(result, compactResult, "status", "error_code", "message", "retryable");
    JsonObject compactData = new JsonObject();
    copyJson(
        data,
        compactData,
        "operation",
        "mode",
        "path",
        "goal",
        "revision",
        "revision_consistent",
        "internal_expansion_performed",
        "line_budget",
        "total_returned_lines",
        "truncated",
        "resolved_targets",
        "coverage_summary",
        "evidence_frontier",
        "plan_progress",
        "read_paths",
        "recommended_next_action",
        "next_read_plan_delta",
        "message",
        "plan_state");
    if (preserveEvidence) {
      copyJson(data, compactData, "evidence", "edit_anchor_pack");
      compactData.addProperty("history_projection", "goal_driven_read_compacted");
    } else {
      compactData.addProperty("history_projection", "stale_goal_driven_read_compacted");
    }
    compactResult.add("data", compactData);
    return new Projection(call.arguments(), GSON.toJson(compactResult));
  }

  private static Projection compactBrowserTest(AgentToolCall call, AgentMessage resultMessage) {
    JsonObject result = parseObject(resultMessage.content());
    if (result == null) return null;
    JsonObject data = object(result.get("data"));
    if (data == null) return null;

    JsonObject compactResult = new JsonObject();
    copyJson(result, compactResult, "status", "error_code", "message", "retryable");
    JsonObject compactData = new JsonObject();
    copyJson(
        data,
        compactData,
        "operation",
        "mode",
        "viewport_signature",
        "passed",
        "failure_kind",
        "failure_reason",
        "deficiency_count",
        "reused",
        "webview_launch_count",
        "coverage_summary",
        "validation_issues",
        "omitted_failure_count",
        "omitted_failure_counts",
        "reading_brief",
        "test_retry_brief",
        "environment_diagnostic",
        "recommended_next_action",
        "plan_state");
    JsonElement rawResults = data.get("scenario_results");
    if (rawResults != null && rawResults.isJsonArray()) {
      List<Map<String, Object>> summaries = new ArrayList<>();
      int remainingFailures = 48;
      int omittedFailures = 0;
      Set<String> failureSignatures = new LinkedHashSet<>();
      Map<String, Integer> omittedFailureCounts = new LinkedHashMap<>();
      for (JsonElement raw : rawResults.getAsJsonArray()) {
        JsonObject scenario = object(raw);
        if (scenario == null) continue;
        String scenarioId = string(scenario.get("id"));
        if (scenarioId.isEmpty()) scenarioId = string(scenario.get("scenario_id"));
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : new String[] {
          "id", "scenario_id", "passed", "failure_kind", "failure_reason",
          "actual_state", "action_trace", "dynamic_coverage"
        }) {
          if (scenario.has(key)) summary.put(key, GSON.fromJson(scenario.get(key), Object.class));
        }
        JsonElement rawFailures = scenario.get("failures");
        if (rawFailures != null && rawFailures.isJsonArray()) {
          List<Object> failures = new ArrayList<>();
          for (JsonElement failure : rawFailures.getAsJsonArray()) {
            String signature = browserFailureSignature(failure, scenarioId);
            if (!failureSignatures.add(signature)) continue;
            if (remainingFailures > 0) {
              failures.add(GSON.fromJson(failure, Object.class));
              remainingFailures--;
            } else {
              omittedFailures++;
              String code = failure.isJsonObject()
                  ? string(failure.getAsJsonObject().get("code")) : "unknown";
              if (code.isEmpty()) code = "unknown";
              omittedFailureCounts.put(code, omittedFailureCounts.getOrDefault(code, 0) + 1);
            }
          }
          if (!failures.isEmpty()) summary.put("failures", failures);
        }
        summaries.add(summary);
      }
      compactData.add("scenario_results", GSON.toJsonTree(summaries));
      if (omittedFailures > 0) {
        int existing = intValue(compactData.get("omitted_failure_count"));
        compactData.addProperty("omitted_failure_count", existing + omittedFailures);
        JsonObject counts = object(compactData.get("omitted_failure_counts"));
        if (counts == null) counts = new JsonObject();
        for (Map.Entry<String, Integer> entry : omittedFailureCounts.entrySet()) {
          counts.addProperty(entry.getKey(), intValue(counts.get(entry.getKey())) + entry.getValue());
        }
        compactData.add("omitted_failure_counts", counts);
      }
    }
    compactData.addProperty("history_projection", "browser_verification_compacted");
    compactResult.add("data", compactData);
    // Browser calls remain valid model-facing transactions. Only their results are compacted;
    // never replace actions/expectations/transition with internal projection metadata.
    return new Projection(call.arguments(), GSON.toJson(compactResult));
  }

  private static String browserFailureSignature(JsonElement raw, String scenarioId) {
    JsonObject failure = object(raw);
    if (failure == null) return raw == null ? "null" : raw.toString();
    return scenarioId + "|"
        + string(failure.get("phase")) + "|"
        + string(failure.get("code")) + "|"
        + string(failure.get("expectation_index")) + "|"
        + string(failure.get("checkpoint_index")) + "|"
        + string(failure.get("action_index")) + "|"
        + string(failure.get("target")) + "|"
        + string(failure.get("expression")) + "|"
        + (failure.has("actual") ? failure.get("actual").toString() : "");
  }

  private static int intValue(JsonElement raw) {
    try {
      return raw == null || raw.isJsonNull() ? 0 : raw.getAsInt();
    } catch (Exception ignored) {
      return 0;
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
      collectFields(payloads, arguments.get("replacements"), "old", "new");
    } else if ("rewrite".equals(tool)) {
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
      Set<Integer> appliedIndexes,
      boolean compactLargePayload) {
    List<RepairUnit> units = repairUnits(tool, source, failedIndexes, appliedIndexes);
    long encodedChars = 0L;
    int payloadChars = 0;
    StringBuilder payloadDigest = new StringBuilder();
    for (RepairUnit unit : units) {
      payloadChars += unit.payload.length();
      encodedChars += encodedChars(unit.payload);
      payloadDigest.append(unit.payload.length()).append(':').append(unit.payload).append(';');
    }
    boolean keepExact = encodedChars <= MAX_REPAIR_PAYLOAD_CHARS
        && (!compactLargePayload || encodedChars <= MAX_RETRY_TEXT_CHARS);
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
