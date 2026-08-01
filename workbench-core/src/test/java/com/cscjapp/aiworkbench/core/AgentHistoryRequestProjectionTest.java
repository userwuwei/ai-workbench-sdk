package com.cscjapp.aiworkbench.core;

import static org.junit.Assert.*;

import com.cscjapp.aiworkbench.api.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AgentHistoryRequestProjectionTest {
  @Test
  public void highRiskSearchReplaceKeepsOnlyRiskSummaryUntilVerification() {
    String source = javascript(120);
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", source);
    Map<String, Object> item = map(
        "index", 0,
        "status", "success",
        "too_large_old", true,
        "risk_level", "high",
        "risk_reasons", Arrays.asList("old_over_40_lines", "scope_coverage_ge_70pct"),
        "deletion_risk", false,
        "scope_coverage_ratio", 0.75d,
        "retained_ratio", 0.82d,
        "function_definition_count", 1,
        "old_preview", source);
    Map<String, Object> data = successData("/project/src/script.js", source);
    data.put("risk_level", "high");
    data.put("risk_reasons", Arrays.asList("old_over_40_lines", "scope_coverage_ge_70pct"));
    data.put("high_risk_replacement_indexes", Collections.singletonList(0));
    data.put("requires_verification", true);
    data.put("results", Collections.singletonList(item));
    List<AgentMessage> history = writeTurn(arguments, ToolResult.success(data));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    ToolArguments compact = projected.get(0).toolCalls().get(0).arguments();
    assertEquals("successful_write_compacted",
        compact.getString("request_projection", ""));
    assertFalse(compact.asMap().toString().contains(source));
    JsonObject compactData = JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject().getAsJsonObject("data");
    assertEquals("high", compactData.get("risk_level").getAsString());
    assertTrue(compactData.get("requires_verification").getAsBoolean());
    assertEquals(0, compactData.getAsJsonArray("high_risk_replacement_indexes")
        .get(0).getAsInt());
    assertEquals("scope_coverage_ge_70pct",
        compactData.getAsJsonArray("risk_reasons").get(1).getAsString());
    JsonObject risk = compactData.getAsJsonArray("results").get(0).getAsJsonObject();
    assertTrue(risk.get("too_large_old").getAsBoolean());
    assertFalse(risk.get("deletion_risk").getAsBoolean());
    assertEquals(0.75d, risk.get("scope_coverage_ratio").getAsDouble(), 0.0001d);
    assertFalse(projected.get(1).content().contains("old_preview"));
    assertFalse(projected.get(1).content().contains(source));
  }

  @Test
  public void destructiveSearchReplaceKeepsRiskEvidenceWithoutRepeatingLargePayload() {
    String replacementSource = javascript(100);
    ToolArguments arguments = searchReplaceArguments(
        "/project/src/script.js", replacementSource);
    Map<String, Object> failure = map(
        "failed_index", 0,
        "error_code", "search_replace_destructive_change",
        "risk_level", "critical",
        "risk_reasons", Arrays.asList(
            "scope_coverage_ge_70pct", "replacement_retains_le_30pct"),
        "deletion_risk", false,
        "scope_coverage_ratio", 0.9d,
        "retained_ratio", 0.2d,
        "function_definition_count", 2);
    Map<String, Object> data = map(
        "path", "/project/src/script.js",
        "current_file_changed", false,
        "applied_count", 0,
        "risk_level", "critical",
        "risk_reasons", Arrays.asList(
            "scope_coverage_ge_70pct", "replacement_retains_le_30pct"),
        "deletion_risk", false,
        "scope_coverage_ratio", 0.9d,
        "retained_ratio", 0.2d,
        "requires_verification", false,
        "recommended_next_action", "search_replace",
        "failures", Collections.singletonList(failure));
    List<AgentMessage> history = writeTurn(
        arguments,
        ToolResult.error(
            "search_replace_destructive_change", "destructive", true, data));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    ToolArguments compact = projected.get(0).toolCalls().get(0).arguments();
    Map<String, Object> anchor = retryAnchor(compact, 0);
    assertFalse(anchor.containsKey("new"));
    assertEquals(Boolean.TRUE, anchor.get("new_truncated"));
    assertTrue(anchor.containsKey("new_head_preview"));
    assertFalse(compact.asMap().toString().contains(replacementSource));
    JsonObject compactResult = JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject();
    JsonObject compactData = compactResult.getAsJsonObject("data");
    assertEquals("search_replace_destructive_change",
        compactResult.get("error_code").getAsString());
    assertEquals("critical", compactData.get("risk_level").getAsString());
    assertEquals(0.2d, compactData.get("retained_ratio").getAsDouble(), 0.0001d);
    JsonObject compactFailure = compactData.getAsJsonArray("failures")
        .get(0).getAsJsonObject();
    assertEquals(2, compactFailure.get("function_definition_count").getAsInt());
    assertEquals("replacement_retains_le_30pct",
        compactFailure.getAsJsonArray("risk_reasons").get(1).getAsString());
  }

  @Test
  public void successfulLargeSearchReplaceIsProjectedWithoutChangingFullHistory() {
    String source = javascript(432);
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", source);
    Map<String, Object> planState = new LinkedHashMap<>();
    planState.put("current_step", Collections.singletonMap("id", "verify"));
    Map<String, Object> data = successData("/project/src/script.js", source);
    data.put("plan_state", planState);
    AgentMessage assistant =
        AgentMessage.assistant(
            "",
            Collections.singletonList(
                new AgentToolCall("write-1", "search_replace", arguments)));
    AgentMessage result =
        AgentMessage.tool(
            "write-1", "search_replace", ToolResultCodec.toJson(ToolResult.success(data)));
    AgentMessage browserCall = AgentMessage.assistant(
        "",
        Collections.singletonList(new AgentToolCall(
            "browser-1", "browser_test", new ToolArguments(map("goal", "verify")))));
    AgentMessage browserResult = AgentMessage.tool(
        "browser-1", "browser_test",
        ToolResultCodec.toJson(ToolResult.success(map("passed", true))));
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.system("system"), AgentMessage.user("task"), assistant, result,
            browserCall, browserResult);

    int originalChars = AgentHistory.estimatedChars(history);
    List<AgentMessage> projected = AgentHistory.forModelRequest(history, 80, 120000);
    int projectedChars = AgentHistory.estimatedChars(projected);
    ToolArguments compact = projected.get(2).toolCalls().get(0).arguments();

    assertEquals("/project/src/script.js", compact.getString("path", ""));
    assertEquals("module_source", compact.getString("file_role", ""));
    assertEquals(1, compact.getInt("replacement_count", 0));
    assertTrue(compact.getInt("original_arguments_chars", 0) > 2048);
    assertTrue(compact.getInt("payload_chars", 0) >= source.length());
    assertTrue(compact.getString("payload_sha256", "").matches("[0-9a-f]{64}"));
    assertFalse(compact.asMap().toString().contains(source));

    JsonObject compactResult = JsonParser.parseString(projected.get(3).content()).getAsJsonObject();
    JsonObject compactData = compactResult.getAsJsonObject("data");
    assertEquals("success", compactResult.get("status").getAsString());
    assertEquals("/project/src/script.js", compactData.get("path").getAsString());
    assertEquals(1, compactData.get("applied_count").getAsInt());
    assertEquals(0, compactData.get("failed_count").getAsInt());
    assertEquals(432, compactData.get("total_lines").getAsInt());
    assertTrue(compactData.has("plan_state"));
    assertFalse(projected.get(3).content().contains(source));

    assertSame(arguments, history.get(2).toolCalls().get(0).arguments());
    assertTrue(history.get(2).toolCalls().get(0).arguments().asMap().toString().contains(source));
    assertEquals(
        source,
        JsonParser.parseString(history.get(3).content())
            .getAsJsonObject()
            .getAsJsonObject("data")
            .get("content")
            .getAsString());
    assertTrue("projected write group must fit within 4KB", projectedChars < 4096);
    assertTrue("projection must remove at least 70%", projectedChars * 10 <= originalChars * 3);
  }

  @Test
  public void onlyLatestBoundedWriteIsPreservedUntilBrowserPasses() {
    String first = javascript(180);
    String second = javascript(190);
    ToolArguments firstArguments = searchReplaceArguments("/project/src/script.js", first);
    ToolArguments secondArguments = searchReplaceArguments("/project/src/script.js", second);
    List<AgentMessage> history = new java.util.ArrayList<>();
    history.add(AgentMessage.assistant("", Collections.singletonList(
        new AgentToolCall("write-1", "search_replace", firstArguments))));
    history.add(AgentMessage.tool(
        "write-1", "search_replace",
        ToolResultCodec.toJson(ToolResult.success(successData("/project/src/script.js", first)))));
    history.add(AgentMessage.assistant("", Collections.singletonList(
        new AgentToolCall("write-2", "search_replace", secondArguments))));
    history.add(AgentMessage.tool(
        "write-2", "search_replace",
        ToolResultCodec.toJson(ToolResult.success(successData("/project/src/script.js", second)))));
    history.add(AgentMessage.assistant("", Collections.singletonList(
        new AgentToolCall("browser", "browser_test", new ToolArguments(map("goal", "verify"))))));
    history.add(AgentMessage.tool(
        "browser", "browser_test",
        ToolResultCodec.toJson(ToolResult.success(
            map("passed", false, "failure_kind", "product_code_failure")))));

    List<AgentMessage> beforePass = AgentHistoryRequestProjection.project(history);
    assertFalse(beforePass.get(0).toolCalls().get(0).arguments().asMap().toString().contains(first));
    assertTrue(beforePass.get(2).toolCalls().get(0).arguments().asMap().toString().contains(second));

    history.add(AgentMessage.assistant("", Collections.singletonList(
        new AgentToolCall("browser-pass", "browser_test", new ToolArguments(map("goal", "verify"))))));
    history.add(AgentMessage.tool(
        "browser-pass", "browser_test",
        ToolResultCodec.toJson(ToolResult.success(map("passed", true)))));
    List<AgentMessage> afterPass = AgentHistoryRequestProjection.project(history);
    assertFalse(afterPass.get(2).toolCalls().get(0).arguments().asMap().toString().contains(second));
  }

  @Test
  public void duplicateFullReadsKeepOnlyLatestSourceCopy() {
    String source = javascript(80);
    ToolArguments arguments = new ToolArguments(map("path", "/project/src/script.js"));
    Map<String, Object> data = map(
        "path", "/project/src/script.js",
        "content", source,
        "revision", "abc",
        "mode", "full_file",
        "full_file", true,
        "total_lines", 80);
    List<AgentMessage> history = Arrays.asList(
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("read-1", "read_file", arguments))),
        AgentMessage.tool("read-1", "read_file", ToolResultCodec.toJson(ToolResult.success(data))),
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("read-2", "read_file", arguments))),
        AgentMessage.tool("read-2", "read_file", ToolResultCodec.toJson(ToolResult.success(data))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);
    assertFalse(JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject().getAsJsonObject("data").has("content"));
    assertTrue(projected.get(1).content().contains("stale_full_read_compacted"));
    assertEquals(source, JsonParser.parseString(projected.get(3).content())
        .getAsJsonObject().getAsJsonObject("data").get("content").getAsString());
  }

  @Test
  public void createAndRewriteKeepRoleCountsAndHashes() {
    String content = javascript(180);
    Map<String, Object> create = new LinkedHashMap<>();
    create.put("path", "/project/src/new.js");
    create.put("file_role", "module_source");
    create.put("content", content);
    Map<String, Object> unit = new LinkedHashMap<>();
    unit.put("kind", "function");
    unit.put("name", "feature");
    unit.put("content", content);
    Map<String, Object> rewrite = new LinkedHashMap<>();
    rewrite.put("path", "/project/src/existing.js");
    rewrite.put("file_role", "module_source");
    rewrite.put("units", Collections.singletonList(unit));
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.assistant(
                "",
                Arrays.asList(
                    new AgentToolCall("create", "create_file", new ToolArguments(create)),
                    new AgentToolCall("rewrite", "rewrite", new ToolArguments(rewrite)))),
            AgentMessage.tool(
                "create",
                "create_file",
                ToolResultCodec.toJson(
                    ToolResult.success(successData("/project/src/new.js", content)))),
            AgentMessage.tool(
                "rewrite",
                "rewrite",
                ToolResultCodec.toJson(
                    ToolResult.success(successData("/project/src/existing.js", content)))),
            AgentMessage.assistant(
                "",
                Collections.singletonList(new AgentToolCall(
                    "browser", "browser_test", new ToolArguments(map("goal", "verify"))))),
            AgentMessage.tool(
                "browser", "browser_test",
                ToolResultCodec.toJson(ToolResult.success(map("passed", true)))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    ToolArguments createProjection = projected.get(0).toolCalls().get(0).arguments();
    ToolArguments rewriteProjection = projected.get(0).toolCalls().get(1).arguments();
    assertEquals(content.length(), createProjection.getInt("content_chars", -1));
    assertEquals(1, rewriteProjection.getInt("unit_count", 0));
    assertEquals("module_source", rewriteProjection.getString("file_role", ""));
    assertTrue(createProjection.getString("payload_sha256", "").matches("[0-9a-f]{64}"));
    assertTrue(rewriteProjection.getString("payload_sha256", "").matches("[0-9a-f]{64}"));
  }

  @Test
  public void createAndRewriteFailuresKeepExactRetryContentWithinRepairBudget() {
    String content = javascript(100);
    Map<String, Object> create = new LinkedHashMap<>();
    create.put("path", "/project/src/new.js");
    create.put("file_role", "module_source");
    create.put("content", content);
    Map<String, Object> unit = new LinkedHashMap<>();
    unit.put("kind", "function");
    unit.put("name", "feature");
    unit.put("content", content);
    Map<String, Object> rewrite = new LinkedHashMap<>();
    rewrite.put("path", "/project/src/existing.js");
    rewrite.put("file_role", "module_source");
    rewrite.put("units", Collections.singletonList(unit));
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.assistant(
                "",
                Arrays.asList(
                    new AgentToolCall("create", "create_file", new ToolArguments(create)),
                    new AgentToolCall("rewrite", "rewrite", new ToolArguments(rewrite)))),
            AgentMessage.tool(
                "create",
                "create_file",
                ToolResultCodec.toJson(ToolResult.error("create_failed", "disk error", true))),
            AgentMessage.tool(
                "rewrite",
                "rewrite",
                ToolResultCodec.toJson(ToolResult.error("rewrite_failed", "anchor error", true))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    ToolArguments createProjection = projected.get(0).toolCalls().get(0).arguments();
    ToolArguments rewriteProjection = projected.get(0).toolCalls().get(1).arguments();
    assertEquals(content, retryAnchor(createProjection, 0).get("content"));
    assertEquals(content, retryAnchor(rewriteProjection, 0).get("content"));
    assertFalse(createProjection.getBoolean("repair_payload_truncated", true));
    assertFalse(rewriteProjection.getBoolean("repair_payload_truncated", true));
  }

  @Test
  public void errorsAndPartialSuccessCompressButKeepRepairEvidence() {
    String content = javascript(180);
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", content);
    Map<String, Object> partial = successData("/project/src/script.js", content);
    partial.put("failed_count", 1);
    partial.put("partial_apply", true);
    partial.put("skipped_indexes", Collections.singletonList(0));
    partial.put("skipped_replacements", Collections.singletonList("exact old anchor"));
    List<AgentMessage> partialHistory =
        writeTurn(arguments, ToolResult.success(partial));
    Map<String, Object> failure = new LinkedHashMap<>();
    failure.put("candidate_window", "exact repair anchor");
    List<AgentMessage> errorHistory =
        writeTurn(
            arguments,
            ToolResult.error("search_replace_conflict", "not found", true, failure));

    List<AgentMessage> partialProjection = AgentHistoryRequestProjection.project(partialHistory);
    List<AgentMessage> errorProjection = AgentHistoryRequestProjection.project(errorHistory);

    ToolArguments partialArguments = partialProjection.get(0).toolCalls().get(0).arguments();
    assertNotSame(arguments, partialArguments);
    assertEquals("write_repair_compacted", partialArguments.getString("request_projection", ""));
    assertTrue(partialArguments.asMap().toString().contains("const oldValue = true"));
    assertEquals(content, retryAnchor(partialArguments, 0).get("new"));
    assertFalse(partialArguments.getBoolean("repair_payload_truncated", true));
    assertTrue(partialProjection.get(1).content().contains("exact old anchor"));
    ToolArguments errorArguments = errorProjection.get(0).toolCalls().get(0).arguments();
    assertNotSame(arguments, errorArguments);
    assertTrue(errorArguments.asMap().toString().contains("const oldValue = true"));
    assertEquals(content, retryAnchor(errorArguments, 0).get("new"));
    assertTrue(errorProjection.get(1).content().contains("exact repair anchor"));
    assertTrue(errorProjection.get(1).content().contains("search_replace_conflict"));
    assertTrue(partialHistory.get(0).toolCalls().get(0).arguments().asMap().toString().contains(content));
  }

  @Test
  public void structuredSearchReplaceFailureKeepsAllReasonsAndBoundsCandidates() {
    String content = javascript(180);
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", content);
    List<Object> failures = new ArrayList<>();
    List<Object> preferred = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      Map<String, Object> candidate = new LinkedHashMap<>();
      candidate.put("failed_index", index);
      candidate.put("start_line", index + 10);
      candidate.put("old", "const candidate" + index + " = true;");
      Map<String, Object> failure = new LinkedHashMap<>();
      failure.put("index", index);
      failure.put("failed_index", index);
      failure.put("status", "failed");
      failure.put("error_code", index == 0
          ? "search_replace_old_too_large" : "search_replace_context_invalid");
      failure.put("actual_matches", index);
      failure.put("matched_lines", Collections.singletonList(index + 10));
      failure.put("candidate_windows", Collections.singletonList(candidate));
      failures.add(failure);
      preferred.add(candidate);
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("path", "/project/src/script.js");
    evidence.put("recommended_next_action", "search_replace");
    evidence.put("recommended_retry", ((Map<?, ?>) preferred.get(0)));
    evidence.put("preferred_retry_old", preferred);
    evidence.put("copyable_old_candidates", preferred);
    evidence.put("failures", failures);
    List<AgentMessage> history = writeTurn(
        arguments,
        ToolResult.error("search_replace_old_too_large", "old 过大", true, evidence));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    JsonObject result = JsonParser.parseString(projected.get(1).content()).getAsJsonObject();
    JsonObject data = result.getAsJsonObject("data");
    assertEquals("search_replace", data.get("recommended_next_action").getAsString());
    assertTrue(data.get("recommended_retry").isJsonObject());
    assertEquals(10, data.getAsJsonArray("failures").size());
    int retainedCandidates = 0;
    for (JsonElement raw : data.getAsJsonArray("failures")) {
      JsonObject failure = raw.getAsJsonObject();
      assertTrue(failure.has("failed_index"));
      assertTrue(failure.has("error_code"));
      assertTrue(failure.has("actual_matches"));
      if (failure.has("candidate_windows")) {
        retainedCandidates += failure.getAsJsonArray("candidate_windows").size();
      }
    }
    assertEquals(8, retainedCandidates);
    assertEquals(8, data.getAsJsonArray("preferred_retry_old").size());
    assertEquals(8, data.getAsJsonArray("copyable_old_candidates").size());
    JsonObject originalResult = JsonParser.parseString(history.get(1).content()).getAsJsonObject();
    assertEquals(10, originalResult.getAsJsonObject("data").getAsJsonArray("failures").size());
  }

  @Test
  public void atomicSearchReplaceFailureKeepsEveryUnwrittenRetryUnit() {
    String firstNew = javascript(90);
    String secondNew = javascript(91);
    ToolArguments arguments = twoReplacementArguments(
        "/project/src/script.js",
        "const firstOld = true;",
        firstNew,
        "const secondOld = true;",
        secondNew);
    Map<String, Object> failure = new LinkedHashMap<>();
    failure.put("index", 1);
    failure.put("failed_index", 1);
    failure.put("status", "failed");
    failure.put("error_code", "search_replace_context_invalid");
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("path", "/project/src/script.js");
    evidence.put("current_file_changed", false);
    evidence.put("applied_count", 0);
    evidence.put("failures", Collections.singletonList(failure));
    List<AgentMessage> history = writeTurn(
        arguments,
        ToolResult.error("search_replace_context_invalid", "预检失败", true, evidence));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    ToolArguments repair = projected.get(0).toolCalls().get(0).arguments();
    assertEquals(2, ((List<?>) repair.get("retry_anchors")).size());
    assertEquals(firstNew, retryAnchor(repair, 0).get("new"));
    assertEquals(secondNew, retryAnchor(repair, 1).get("new"));
    JsonObject result = JsonParser.parseString(projected.get(1).content()).getAsJsonObject();
    assertEquals(1, result.getAsJsonObject("data").getAsJsonArray("failures").size());
  }

  @Test
  public void realOrderLargeRetryableFailureStaysProtocolValidAndWithinHardBudget() {
    String content = String.join("", Collections.nCopies(150000, "n"));
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", content);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("path", "/project/src/script.js");
    evidence.put("failed_indexes", Collections.singletonList(0));
    evidence.put(
        "candidate_window", "line 42: const oldValue = false;" + String.join("", Collections.nCopies(150000, "c")));
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.system("system"),
            AgentMessage.user("修复脚本"),
            AgentMessage.assistant(
                "",
                Collections.singletonList(
                    new AgentToolCall("write", "search_replace", arguments))),
            AgentMessage.tool(
                "write",
                "search_replace",
                ToolResultCodec.toJson(
                    ToolResult.error(
                        "search_replace_conflict", "精确 old 未命中", true, evidence))));

    List<AgentMessage> request = AgentHistory.forModelRequest(history, 80, 120000);
    List<AgentMessage> rawBounded = AgentHistory.bounded(history, 80, 120000);

    assertEquals(4, request.size());
    assertEquals(AgentMessage.Role.SYSTEM, request.get(0).role());
    assertEquals(AgentMessage.Role.USER, request.get(1).role());
    assertEquals(AgentMessage.Role.ASSISTANT, request.get(2).role());
    assertEquals(AgentMessage.Role.TOOL, request.get(3).role());
    assertEquals("write", request.get(2).toolCalls().get(0).id());
    assertEquals("write", request.get(3).toolCallId());
    assertTrue(AgentHistory.estimatedChars(history) > 120000);
    assertTrue(AgentHistory.estimatedChars(request) <= 120000);
    assertEquals(2, rawBounded.size());
    assertEquals("修复脚本", rawBounded.get(1).content());
    assertTrue(AgentHistory.estimatedChars(rawBounded) <= 120000);
    ToolArguments repairArguments = request.get(2).toolCalls().get(0).arguments();
    assertFalse(repairArguments.asMap().toString().contains(content));
    assertTrue(repairArguments.asMap().toString().contains("const oldValue = true"));
    assertTrue(repairArguments.getBoolean("repair_payload_truncated", false));
    Map<String, Object> extremeAnchor = retryAnchor(repairArguments, 0);
    assertFalse(extremeAnchor.containsKey("new"));
    assertEquals(Boolean.TRUE, extremeAnchor.get("new_truncated"));
    assertEquals(512, String.valueOf(extremeAnchor.get("new_head_preview")).length());
    assertEquals(512, String.valueOf(extremeAnchor.get("new_tail_preview")).length());
    assertTrue(request.get(3).content().contains("search_replace_conflict"));
    assertTrue(request.get(3).content().contains("line 42"));
    assertTrue(history.get(2).toolCalls().get(0).arguments().asMap().toString().contains(content));
  }

  @Test
  public void partialWriteKeepsFailedNewAndDoesNotRepeatAppliedUnit() {
    String failedContent = javascript(120);
    String appliedContent = "const alreadyAppliedMarker = true;";
    ToolArguments arguments =
        twoReplacementArguments(
            "/project/src/script.js",
            "const failedOld = true;",
            failedContent,
            "const appliedOld = true;",
            appliedContent);
    Map<String, Object> partial = successData("/project/src/script.js", appliedContent);
    partial.put("partial_apply", true);
    partial.put("failed_count", 1);
    partial.put("requested_count", 2);
    partial.put("applied_count", 1);
    partial.put("skipped_indexes", Collections.singletonList(0));
    partial.put("applied_indexes", Collections.singletonList(1));
    partial.put("partial_failure_code", "search_replace_batch_conflict");
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.system("system"),
            AgentMessage.user("批量修复脚本"),
            AgentMessage.assistant(
                "",
                Collections.singletonList(
                    new AgentToolCall("write", "search_replace", arguments))),
            AgentMessage.tool(
                "write", "search_replace", ToolResultCodec.toJson(ToolResult.success(partial))));

    List<AgentMessage> request = AgentHistory.forModelRequest(history, 80, 120000);

    assertEquals(4, request.size());
    assertTrue(AgentHistory.estimatedChars(request) <= 120000);
    ToolArguments repairArguments = request.get(2).toolCalls().get(0).arguments();
    assertEquals(1, ((List<?>) repairArguments.get("retry_anchors")).size());
    assertEquals(0, ((Number) retryAnchor(repairArguments, 0).get("index")).intValue());
    assertEquals(failedContent, retryAnchor(repairArguments, 0).get("new"));
    assertFalse(repairArguments.asMap().toString().contains(appliedContent));
    JsonObject result = JsonParser.parseString(request.get(3).content()).getAsJsonObject();
    JsonObject data = result.getAsJsonObject("data");
    assertEquals(0, data.getAsJsonArray("failed_indexes").get(0).getAsInt());
    assertEquals(1, data.getAsJsonArray("applied_indexes").get(0).getAsInt());
    assertEquals("search_replace_batch_conflict", data.get("partial_failure_code").getAsString());
  }

  @Test
  public void countMismatchWithoutFailedCountInfersUnappliedIndexAndRepairAnchor() {
    String failedContent = javascript(100);
    String appliedContent = "const countAppliedMarker = true;";
    ToolArguments arguments =
        twoReplacementArguments(
            "/project/src/script.js",
            "const appliedOld = true;",
            appliedContent,
            "const failedOld = true;",
            failedContent);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("path", "/project/src/script.js");
    data.put("count", 2);
    data.put("applied_count", 1);
    data.put("no_change_count", 0);
    Map<String, Object> appliedResult = new LinkedHashMap<>();
    appliedResult.put("index", 0);
    appliedResult.put("status", "success");
    data.put("results", Collections.singletonList(appliedResult));
    List<AgentMessage> history = writeTurn(arguments, ToolResult.success(data));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    ToolArguments repair = projected.get(0).toolCalls().get(0).arguments();
    assertEquals("write_repair_compacted", repair.getString("request_projection", ""));
    assertEquals(1, ((Number) retryAnchor(repair, 0).get("index")).intValue());
    assertEquals(failedContent, retryAnchor(repair, 0).get("new"));
    assertFalse(repair.asMap().toString().contains(appliedContent));
    JsonObject result = JsonParser.parseString(projected.get(1).content()).getAsJsonObject();
    assertEquals(1, result.getAsJsonObject("data").getAsJsonArray("failed_indexes").get(0).getAsInt());
  }

  @Test
  public void fourHundredThirtyTwoLineFailureKeepsExactNewForImmediateRetry() {
    String content = javascript(432);
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", content);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("path", "/project/src/script.js");
    evidence.put("failed_indexes", Collections.singletonList(0));
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.system("system"),
            AgentMessage.user("修复脚本"),
            AgentMessage.assistant(
                "",
                Collections.singletonList(
                    new AgentToolCall("write", "search_replace", arguments))),
            AgentMessage.tool(
                "write",
                "search_replace",
                ToolResultCodec.toJson(
                    ToolResult.error("search_replace_conflict", "old 未命中", true, evidence))));

    List<AgentMessage> request = AgentHistory.forModelRequest(history, 80, 120000);

    ToolArguments repair = request.get(2).toolCalls().get(0).arguments();
    assertEquals(content, retryAnchor(repair, 0).get("new"));
    assertFalse(repair.getBoolean("repair_payload_truncated", true));
    assertTrue(AgentHistory.estimatedChars(request) <= 120000);
  }

  @Test
  public void smallSuccessfulWriteRemainsVerbatim() {
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", "const value = 2;");
    List<AgentMessage> history =
        writeTurn(
            arguments,
            ToolResult.success(successData("/project/src/script.js", "const value = 2;\n")));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    assertSame(arguments, projected.get(0).toolCalls().get(0).arguments());
    assertEquals(history.get(1).content(), projected.get(1).content());
  }

  @Test
  public void readPlanHistoryKeepsSingleEvidenceCopyAndDropsInternalBatchPayloads() {
    String source = "function resizeCanvas() { return canvas.width; }";
    ToolArguments arguments =
        new ToolArguments(
            map(
                "path", "/project/index.html",
                "goal", "fix resize",
                "evidence_requirements",
                Collections.singletonList(
                    map(
                        "id", "resize_flow",
                        "question", "where is resize registered",
                        "evidence_types", Arrays.asList("definition", "event_binding"),
                        "signals", Collections.singletonList("resizeCanvas")))));
    Map<String, Object> evidence =
        map(
            "evidence_id", "ev_resize",
            "requirement_ids", Collections.singletonList("resize_flow"),
            "role", "definition",
            "content", source);
    Map<String, Object> data =
        map(
            "operation", "read_plan",
            "mode", "goal_driven_evidence_batch",
            "path", "/project/index.html",
            "revision", String.join("", Collections.nCopies(64, "a")),
            "evidence", Collections.singletonList(evidence),
            "coverage_summary", map("ready_for_edit", true),
            "items", Collections.singletonList(map("result", map("content", source))),
            "content", source,
            "recommended_next_action", "search_replace");
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.assistant(
                "",
                Collections.singletonList(new AgentToolCall("read-plan", "read_plan", arguments))),
            AgentMessage.tool(
                "read-plan", "read_plan", ToolResultCodec.toJson(ToolResult.success(data))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    assertSame(arguments, projected.get(0).toolCalls().get(0).arguments());
    JsonObject result = JsonParser.parseString(projected.get(1).content()).getAsJsonObject();
    JsonObject compactData = result.getAsJsonObject("data");
    assertTrue(compactData.has("evidence"));
    assertFalse(compactData.has("items"));
    assertFalse(compactData.has("content"));
    assertEquals("goal_driven_read_compacted", compactData.get("history_projection").getAsString());
    assertEquals(projected.get(1).content().indexOf(source), projected.get(1).content().lastIndexOf(source));
  }

  @Test
  public void readPlanHistoryKeepsOnlyLatestEvidenceForSamePathAndRevision() {
    String revision = String.join("", Collections.nCopies(64, "b"));
    ToolArguments firstArguments = new ToolArguments(map(
        "path", "/project/app.js",
        "goal", "inspect state",
        "evidence_requirements", Collections.singletonList(map(
            "id", "state", "question", "find state", "signals", Collections.singletonList("state")))));
    ToolArguments secondArguments = new ToolArguments(map(
        "path", "/project/app.js",
        "goal", "inspect state again",
        "evidence_requirements", Collections.singletonList(map(
            "id", "render", "question", "find render", "signals", Collections.singletonList("render")))));
    Map<String, Object> firstData = map(
        "operation", "read_plan",
        "mode", "goal_driven_evidence_batch",
        "path", "/project/app.js",
        "revision", revision,
        "resolved_targets", Collections.singletonList(map("start_line", 1, "end_line", 2)),
        "evidence", Collections.singletonList(map(
            "evidence_id", "ev_state", "content", "const state = {};", "sha256", "first")),
        "edit_anchor_pack", Collections.singletonList(map("copyable_old", "const state = {};")),
        "coverage_summary", map("ready_for_edit", false),
        "evidence_frontier", map("can_request_delta", true),
        "plan_progress", map("has_new_information", true),
        "recommended_next_action", "read_plan");
    Map<String, Object> secondData = map(
        "operation", "read_plan",
        "mode", "goal_driven_evidence_batch",
        "path", "/project/app.js",
        "revision", revision,
        "resolved_targets", Collections.singletonList(map("start_line", 8, "end_line", 10)),
        "evidence", Collections.singletonList(map(
            "evidence_id", "ev_render", "content", "function render() {}", "sha256", "second")),
        "edit_anchor_pack", Collections.singletonList(map("copyable_old", "function render() {}")),
        "coverage_summary", map("ready_for_edit", true),
        "evidence_frontier", map("can_request_delta", false),
        "plan_progress", map("has_new_information", true),
        "recommended_next_action", "search_replace");
    List<AgentMessage> history = Arrays.asList(
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("read-1", "read_plan", firstArguments))),
        AgentMessage.tool("read-1", "read_plan", ToolResultCodec.toJson(ToolResult.success(firstData))),
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("read-2", "read_plan", secondArguments))),
        AgentMessage.tool("read-2", "read_plan", ToolResultCodec.toJson(ToolResult.success(secondData))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    JsonObject stale = JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject().getAsJsonObject("data");
    assertFalse(stale.has("evidence"));
    assertFalse(stale.has("edit_anchor_pack"));
    assertTrue(stale.has("evidence_frontier"));
    assertTrue(stale.has("plan_progress"));
    assertEquals("stale_goal_driven_read_compacted", stale.get("history_projection").getAsString());

    JsonObject latest = JsonParser.parseString(projected.get(3).content())
        .getAsJsonObject().getAsJsonObject("data");
    assertTrue(latest.getAsJsonArray("evidence").toString().contains("function render"));
    assertTrue(latest.has("edit_anchor_pack"));
    assertEquals("goal_driven_read_compacted", latest.get("history_projection").getAsString());
  }

  @Test
  public void browserHistoryKeepsFormalScenarioArgumentsAndCompactsOnlyResults() {
    Map<String, Object> arguments =
        map(
            "entry_path", "index.html",
            "goal", "验证启动",
            "scenarios",
            Collections.singletonList(
                map(
                    "id", "start",
                    "description", "点击后启动",
                    "actions", Collections.singletonList(map("type", "click", "selector", "#start")),
                    "expectations",
                    Collections.singletonList(
                        map("type", "js_boolean", "expression", "window.secretProtocol === true")))));
    Map<String, Object> data =
        map(
            "operation", "browser_test",
            "mode", "goal_driven_verification_transaction",
            "source_revision", "rev-1",
            "test_plan_hash", "plan-1",
            "passed", false,
            "failure_kind", "product_code_failure",
            "failure_reason", "两个 Canvas 比例不一致",
            "scenario_results",
            Collections.singletonList(
                map(
                    "id", "start",
                    "passed", false,
                    "failure_kind", "product_code_failure",
                    "failures", Collections.singletonList(
                        map(
                            "phase", "layout",
                            "code", "canvas_aspect_ratio_mismatch",
                            "target", "#gameCanvas")),
                    "actual_state", map("baseline", false, "post", false),
                    "compiled_steps", String.join("", Collections.nCopies(100, "internal-step")))),
            "reading_brief", map("path", "index.html", "signals", Collections.singletonList("#start")),
            "test_retry_brief", map("issue", "baseline_already_true", "recommended_tool", "browser_test"),
            "recommended_next_action", "read_plan");
    List<AgentMessage> projected =
        AgentHistoryRequestProjection.project(
            Arrays.asList(
                AgentMessage.assistant(
                    "",
                    Collections.singletonList(
                        new AgentToolCall("browser", "browser_test", new ToolArguments(arguments)))),
                AgentMessage.tool(
                    "browser", "browser_test", ToolResultCodec.toJson(ToolResult.success(data)))));

    ToolArguments compact = projected.get(0).toolCalls().get(0).arguments();
    assertFalse(compact.has("request_projection"));
    assertTrue(compact.asMap().toString().contains("actions"));
    assertTrue(compact.asMap().toString().contains("expectations"));
    assertTrue(compact.asMap().toString().contains("window.secretProtocol"));
    JsonObject compactData =
        JsonParser.parseString(projected.get(1).content())
            .getAsJsonObject()
            .getAsJsonObject("data");
    assertEquals("product_code_failure", compactData.get("failure_kind").getAsString());
    assertEquals("两个 Canvas 比例不一致", compactData.get("failure_reason").getAsString());
    assertEquals("read_plan", compactData.get("recommended_next_action").getAsString());
    assertTrue(compactData.has("reading_brief"));
    assertTrue(compactData.has("test_retry_brief"));
    assertTrue(compactData.getAsJsonObject("test_retry_brief").toString()
        .contains("baseline_already_true"));
    assertTrue(compactData.toString().contains("canvas_aspect_ratio_mismatch"));
    assertTrue(compactData.toString().contains("#gameCanvas"));
    assertFalse(compactData.has("source_revision"));
    assertFalse(compactData.has("test_plan_hash"));
    assertFalse(projected.get(1).content().contains("internal-step"));
  }

  @Test
  public void browserHistoryDeduplicatesAndBoundsFailuresWithCategoryCounts() {
    List<Object> firstFailures = new java.util.ArrayList<>();
    for (int index = 0; index < 50; index++) {
      firstFailures.add(map(
          "phase", "layout",
          "code", "failure-" + index,
          "target", "#target-" + index,
          "actual", map("value", index)));
    }
    List<Object> scenarios = Arrays.asList(
        map(
            "id", "first",
            "passed", false,
            "failures", firstFailures),
        map(
            "id", "second",
            "passed", false,
            "failures", Collections.singletonList(firstFailures.get(0))));
    Map<String, Object> data = map(
        "operation", "browser_test",
        "passed", false,
        "failure_kind", "product_code_failure",
        "deficiency_count", 50,
        "scenario_results", scenarios);
    List<AgentMessage> projected = AgentHistoryRequestProjection.project(Arrays.asList(
        AgentMessage.assistant("", Collections.singletonList(new AgentToolCall(
            "browser", "browser_test", new ToolArguments(map("goal", "verify"))))),
        AgentMessage.tool(
            "browser", "browser_test", ToolResultCodec.toJson(ToolResult.success(data)))));

    JsonObject compactData = JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject().getAsJsonObject("data");
    assertEquals(48, compactData.getAsJsonArray("scenario_results")
        .get(0).getAsJsonObject().getAsJsonArray("failures").size());
    assertFalse(compactData.getAsJsonArray("scenario_results")
        .get(1).getAsJsonObject().has("failures"));
    assertEquals(3, compactData.get("omitted_failure_count").getAsInt());
    assertEquals(1, compactData.getAsJsonObject("omitted_failure_counts")
        .get("failure-48").getAsInt());
    assertEquals(1, compactData.getAsJsonObject("omitted_failure_counts")
        .get("failure-0").getAsInt());
    assertEquals(50, compactData.get("deficiency_count").getAsInt());
  }

  @Test
  public void characterBudgetCountsToolArgumentsAndToolResults() {
    String large = String.join("", Collections.nCopies(6000, "x"));
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("path", "/project/read.txt");
    arguments.put("query", large);
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.system("system"),
            AgentMessage.user("old demand"),
            AgentMessage.assistant(
                "",
                Collections.singletonList(
                    new AgentToolCall("read", "read_file", new ToolArguments(arguments)))),
            AgentMessage.tool("read", "read_file", "{\"status\":\"success\"}"),
            AgentMessage.user("current demand"));

    List<AgentMessage> bounded = AgentHistory.bounded(history, 80, 4096);

    assertEquals(2, bounded.size());
    assertEquals(AgentMessage.Role.SYSTEM, bounded.get(0).role());
    assertEquals("current demand", bounded.get(1).content());
    assertTrue(AgentHistory.estimatedChars(history) > 6000);
  }

  @Test
  public void characterBudgetCountsLargeToolResultBodies() {
    String largeResult = String.join("", Collections.nCopies(6000, "r"));
    List<AgentMessage> history =
        Arrays.asList(
            AgentMessage.system("system"),
            AgentMessage.user("old demand"),
            AgentMessage.assistant(
                "",
                Collections.singletonList(
                    new AgentToolCall(
                        "read",
                        "read_file",
                        new ToolArguments(Collections.singletonMap("path", "/project/read.txt"))))),
            AgentMessage.tool("read", "read_file", largeResult),
            AgentMessage.user("current demand"));

    List<AgentMessage> bounded = AgentHistory.bounded(history, 80, 4096);

    assertEquals(2, bounded.size());
    assertEquals("current demand", bounded.get(1).content());
  }

  @Test
  public void agentEngineSendsProjectionButKeepsOriginalMessages() {
    String content = javascript(220);
    ToolArguments arguments = searchReplaceArguments("/project/src/script.js", content);
    AtomicInteger round = new AtomicInteger();
    AtomicReference<ModelRequest> secondRequest = new AtomicReference<>();
    ModelGateway gateway =
        (request, observer) -> {
          if (round.incrementAndGet() == 1) {
            observer.onComplete(
                new ModelResponse(
                    "",
                    "tool_calls",
                    Collections.singletonList(
                        new AgentToolCall("write", "search_replace", arguments))));
          } else {
            secondRequest.set(request);
            observer.onComplete(new ModelResponse("done", "stop", Collections.emptyList()));
          }
          return Cancellable.NONE;
        };
    AgentTool writeTool =
        new AgentTool() {
          public ToolSpec spec() {
            return new ToolSpec("search_replace", "", Collections.singletonMap("type", "object"));
          }

          public Cancellable execute(ToolContext context, ToolArguments args, ToolCallback callback) {
            callback.onComplete(
                ToolResult.success(successData("/project/src/script.js", content)));
            return Cancellable.NONE;
          }
        };
    AgentEngine engine =
        new AgentEngine(
            definition(Collections.singletonList(writeTool)),
            gateway,
            new ModelEndpoint("http://localhost", "", "model", 0, false, false),
            (request, callback) -> Cancellable.NONE,
            Runnable::run,
            "session",
            "workspace",
            false,
            4);
    AtomicReference<String> output = new AtomicReference<>();

    engine.submit("task", observer(output));

    assertEquals("done", output.get());
    assertNotNull(secondRequest.get());
    AgentMessage projectedCall = secondRequest.get().messages().get(2);
    assertTrue(projectedCall.toolCalls().get(0).arguments().asMap().toString().contains(content));
    List<AgentMessage> fullHistory = engine.messages();
    assertTrue(fullHistory.get(2).toolCalls().get(0).arguments().asMap().toString().contains(content));
    assertEquals(
        content,
        JsonParser.parseString(fullHistory.get(3).content())
            .getAsJsonObject()
            .getAsJsonObject("data")
            .get("content")
            .getAsString());
  }

  private static List<AgentMessage> writeTurn(ToolArguments arguments, ToolResult result) {
    return Arrays.asList(
        AgentMessage.assistant(
            "",
            Collections.singletonList(
                new AgentToolCall("write", "search_replace", arguments))),
        AgentMessage.tool("write", "search_replace", ToolResultCodec.toJson(result)));
  }

  private static ToolArguments searchReplaceArguments(String path, String content) {
    Map<String, Object> replacement = new LinkedHashMap<>();
    replacement.put("old", "const oldValue = true;");
    replacement.put("new", content);
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("path", path);
    arguments.put("file_role", "module_source");
    arguments.put("replacements", Collections.singletonList(replacement));
    return new ToolArguments(arguments);
  }

  private static ToolArguments twoReplacementArguments(
      String path, String old0, String new0, String old1, String new1) {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("old", old0);
    first.put("new", new0);
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("old", old1);
    second.put("new", new1);
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("path", path);
    arguments.put("file_role", "module_source");
    arguments.put("replacements", Arrays.asList(first, second));
    return new ToolArguments(arguments);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> retryAnchor(ToolArguments arguments, int index) {
    List<?> anchors = (List<?>) arguments.get("retry_anchors");
    return (Map<String, Object>) anchors.get(index);
  }

  private static Map<String, Object> successData(String path, String content) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("path", path);
    data.put("changed", true);
    data.put("applied_count", 1);
    data.put("failed_count", 0);
    data.put("total_lines", content.split("\\n", -1).length - 1);
    data.put("content", content);
    return data;
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index + 1 < values.length; index += 2) {
      result.put(String.valueOf(values[index]), values[index + 1]);
    }
    return result;
  }

  private static String javascript(int lines) {
    StringBuilder source = new StringBuilder();
    for (int index = 0; index < lines; index++) {
      source.append("function feature").append(index).append("(){return ")
          .append(index).append(";} // generated source line\n");
    }
    return source.toString();
  }

  private static WorkbenchDefinition definition(List<AgentTool> tools) {
    return new WorkbenchDefinition() {
      public String id() {
        return "test";
      }

      public String displayName() {
        return "test";
      }

      public List<PromptContributor> promptContributors() {
        return Collections.emptyList();
      }

      public List<ContextProvider> contextProviders() {
        return Collections.emptyList();
      }

      public List<AgentTool> tools() {
        return tools;
      }

      public List<ToolPolicy> toolPolicies() {
        return Collections.emptyList();
      }

      public List<TaskValidator> validators() {
        return Collections.emptyList();
      }

      public WorkbenchHost host() {
        return new WorkbenchHost() {
          public void openArtifact(String path) {}

          public void refreshArtifacts() {}

          public void handleAction(String action, ToolArguments arguments) {}

          public void onEvent(WorkbenchEvent event) {}
        };
      }
    };
  }

  private static AgentObserver observer(AtomicReference<String> output) {
    return new AgentObserver() {
      public void onState(String state) {}

      public void onDelta(String content, String reasoning) {}

      public void onToolStarted(String id, String name, ToolArguments arguments) {}

      public void onToolProgress(
          String id, String stage, long current, long total, String message) {}

      public void onToolCompleted(String id, String name, ToolResult result) {}

      public void onValidation(ValidationResult result) {}

      public void onFinal(String content) {
        output.set(content);
      }

      public void onError(Throwable error) {
        throw new AssertionError(error);
      }
    };
  }
}
