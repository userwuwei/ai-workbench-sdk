package com.cscjapp.aiworkbench.core;

import static org.junit.Assert.*;

import com.cscjapp.aiworkbench.api.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    List<AgentMessage> history =
        Arrays.asList(AgentMessage.system("system"), AgentMessage.user("task"), assistant, result);

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
                    ToolResult.success(successData("/project/src/existing.js", content)))));

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
    assertFalse(projectedCall.toolCalls().get(0).arguments().asMap().toString().contains(content));
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


  @Test
  public void staleReadBodyIsRemovedAfterSuccessfulNewRevision() {
    String oldContent = javascript(180);
    Map<String, Object> readArgs = Collections.singletonMap("path", "/project/src/script.js");
    Map<String, Object> readData = new LinkedHashMap<>();
    readData.put("path", "/project/src/script.js");
    readData.put("content", oldContent);
    readData.put("lines", Arrays.asList(oldContent.split("\\n")));
    String replacement = javascript(220);
    ToolArguments writeArgs = searchReplaceArguments("/project/src/script.js", replacement);
    List<AgentMessage> history = Arrays.asList(
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("read", "read_file", new ToolArguments(readArgs)))),
        AgentMessage.tool("read", "read_file",
            ToolResultCodec.toJson(ToolResult.success(readData))),
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("write", "search_replace", writeArgs))),
        AgentMessage.tool("write", "search_replace",
            ToolResultCodec.toJson(ToolResult.success(
                successData("/project/src/script.js", replacement)))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    assertEquals("stale_read_compacted", projected.get(0).toolCalls().get(0)
        .arguments().getString("request_projection", ""));
    JsonObject readResult = JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject().getAsJsonObject("data");
    assertFalse(readResult.has("content"));
    assertFalse(readResult.has("lines"));
    assertTrue(readResult.get("content_compacted").getAsBoolean());
    assertEquals(64, readResult.get("content_hash").getAsString().length());
  }

  @Test
  public void browserProjectionKeepsOnlyLatestRepairSignalsAndOneAudit() {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("action", "evaluate_js");
    step.put("check_id", "restart");
    step.put("script", "document.body.dataset.state === 'running'");
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("operation", "run_steps");
    arguments.put("steps", Collections.singletonList(step));
    arguments.put("interaction_check_ids", Collections.singletonList("restart"));
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("passed", true);
    audit.put("covered_check_ids", Collections.singletonList("restart"));
    Map<String, Object> layout = new LinkedHashMap<>();
    layout.put("passed", true);
    layout.put("document_width", 99999);
    layout.put("interaction_audit", audit);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("operation", "browser_test");
    data.put("browser_operation", "run_steps");
    data.put("passed", true);
    data.put("steps_passed", true);
    data.put("steps", Collections.nCopies(30, step));
    data.put("interaction_audit", audit);
    data.put("layout_audit", layout);
    data.put("test_manifest", Collections.singletonMap("selectors",
        Collections.singletonList("#restart")));
    List<AgentMessage> history = Arrays.asList(
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("browser", "browser_test", new ToolArguments(arguments)))),
        AgentMessage.tool("browser", "browser_test",
            ToolResultCodec.toJson(ToolResult.success(data))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    ToolArguments compactArgs = projected.get(0).toolCalls().get(0).arguments();
    assertEquals(1, compactArgs.getInt("step_count", 0));
    assertFalse(compactArgs.asMap().containsKey("steps"));
    JsonObject compactData = JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject().getAsJsonObject("data");
    assertFalse(compactData.has("steps"));
    assertTrue(compactData.has("interaction_audit"));
    assertFalse(compactData.getAsJsonObject("layout_audit").has("document_width"));
    assertFalse(compactData.getAsJsonObject("layout_audit").has("interaction_audit"));
    assertTrue(compactData.has("test_manifest"));
  }

  @Test
  public void onlyLatestBrowserFailureKeepsRepairDetails() {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("operation", "run_steps");
    arguments.put("steps", Collections.singletonList(
        Collections.singletonMap("action", "assert_selector_exists")));
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("operation", "browser_test");
    first.put("passed", false);
    first.put("failure_class", "selector_not_found");
    first.put("failed_check_id", "direction");
    first.put("selector_candidates", Arrays.asList("#old", "#candidate"));
    Map<String, Object> second = new LinkedHashMap<>(first);
    second.put("failed_check_id", "restart");
    second.put("selector_candidates", Collections.singletonList("#restart"));
    List<AgentMessage> history = Arrays.asList(
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("browser-1", "browser_test", new ToolArguments(arguments)))),
        AgentMessage.tool("browser-1", "browser_test",
            ToolResultCodec.toJson(ToolResult.success(first))),
        AgentMessage.assistant("", Collections.singletonList(
            new AgentToolCall("browser-2", "browser_test", new ToolArguments(arguments)))),
        AgentMessage.tool("browser-2", "browser_test",
            ToolResultCodec.toJson(ToolResult.success(second))));

    List<AgentMessage> projected = AgentHistoryRequestProjection.project(history);

    assertEquals("browser_failure_superseded", projected.get(0).toolCalls().get(0)
        .arguments().getString("request_projection", ""));
    assertEquals("browser_failure_compacted", projected.get(2).toolCalls().get(0)
        .arguments().getString("request_projection", ""));
    JsonObject oldData = JsonParser.parseString(projected.get(1).content())
        .getAsJsonObject().getAsJsonObject("data");
    JsonObject latestData = JsonParser.parseString(projected.get(3).content())
        .getAsJsonObject().getAsJsonObject("data");
    assertFalse(oldData.has("selector_candidates"));
    assertTrue(latestData.has("selector_candidates"));
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
