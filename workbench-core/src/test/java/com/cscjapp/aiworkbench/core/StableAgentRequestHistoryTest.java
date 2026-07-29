package com.cscjapp.aiworkbench.core;

import static org.junit.Assert.*;

import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class StableAgentRequestHistoryTest {
  private static final Gson GSON = new Gson();

  @Test
  public void completedTransactionsRemainByteStableWhenLaterStateChanges() {
    List<AgentMessage> canonical = new ArrayList<>();
    canonical.add(AgentMessage.system("system"));
    canonical.add(AgentMessage.user("task"));
    StableAgentRequestHistory history = new StableAgentRequestHistory();
    history.reset(canonical, "task");
    history.prepare(canonical, Collections.emptyList(), "task");

    appendWrite(canonical, "write-1", repeat('a', 5000));
    List<AgentMessage> first = history.prepare(canonical, Collections.emptyList(), "task").messages;
    String frozenWrite = encode(first.get(2)) + encode(first.get(3));

    appendWrite(canonical, "write-2", repeat('b', 5000));
    appendBrowser(canonical, "browser", true);
    List<AgentMessage> second = history.prepare(canonical, Collections.emptyList(), "task").messages;

    assertEquals(frozenWrite, encode(second.get(2)) + encode(second.get(3)));
    assertTrue(second.size() > first.size());
    assertEquals(8, canonical.size());
  }

  @Test
  public void softThresholdCompactsOnceBelowSixtyPercent() {
    List<AgentMessage> canonical = largeHistory(22, 10_000);
    List<String> original = encodeAll(canonical);
    StableAgentRequestHistory history = new StableAgentRequestHistory();
    history.reset(canonical, "task");

    StableAgentRequestHistory.Projection first =
        history.prepare(canonical, Collections.emptyList(), "task");

    assertEquals("soft_78", first.usage.projectionMode());
    assertTrue(first.usage.inputTokens() <= StableAgentRequestHistory.SOFT_TARGET_TOKENS);
    assertTrue(first.messages.get(1).content().startsWith(StableAgentRequestHistory.MEMORY_MARKER));
    assertEquals(original, encodeAll(canonical));

    List<String> once = encodeAll(first.messages);
    StableAgentRequestHistory.Projection again =
        history.prepare(canonical, Collections.emptyList(), "task");
    assertEquals(once, encodeAll(again.messages));

    canonical.add(AgentMessage.assistant("next", Collections.emptyList()));
    StableAgentRequestHistory.Projection appended =
        history.prepare(canonical, Collections.emptyList(), "task");
    assertEquals("soft_78", appended.usage.projectionMode());
    assertEquals(once, encodeAll(appended.messages.subList(0, once.size())));
  }

  @Test
  public void hardThresholdTargetsHalfWindowAndNeverBreaksToolGroups() {
    List<AgentMessage> canonical = new ArrayList<>();
    canonical.add(AgentMessage.system("system"));
    canonical.add(AgentMessage.user("task"));
    for (int index = 0; index < 30; index++) {
      String id = "call-" + index;
      canonical.add(AgentMessage.assistant("", Collections.singletonList(
          new AgentToolCall(id, "read_file", new ToolArguments(map("path", "/p/" + index))))));
      canonical.add(AgentMessage.tool(id, "read_file", ToolResultCodec.toJson(
          ToolResult.success(map("path", "/p/" + index, "content", repeat('中', 10_000))))));
    }
    StableAgentRequestHistory history = new StableAgentRequestHistory();
    history.reset(canonical, "task");

    StableAgentRequestHistory.Projection result =
        history.prepare(canonical, Collections.emptyList(), "task");

    assertEquals("hard_88", result.usage.projectionMode());
    assertTrue(result.usage.inputTokens() <= StableAgentRequestHistory.HARD_TARGET_TOKENS);
    assertEquals(result.messages.size(), AgentHistory.sanitize(result.messages).size());
    assertTrue(result.usage.inputTokens() <= StableAgentRequestHistory.MAX_INPUT_TOKENS);
  }

  @Test
  public void summaryIsDeterministicAndKeepsCurrentStructuredFacts() {
    List<AgentMessage> canonical = largeHistory(22, 10_000);
    Map<String, Object> plan = map("current_step", map("id", "verify"));
    canonical.add(AgentMessage.assistant("", Collections.singletonList(
        new AgentToolCall("syntax", "syntax_check", new ToolArguments(map("path", "/p/index.html"))))));
    canonical.add(AgentMessage.tool("syntax", "syntax_check", ToolResultCodec.toJson(
        ToolResult.success(map(
            "path", "/p/index.html",
            "revision", "r2",
            "passed", false,
            "failure_kind", "product_code_failure",
            "recommended_next_action", "search_replace",
            "plan_state", plan)))));

    StableAgentRequestHistory left = new StableAgentRequestHistory();
    StableAgentRequestHistory right = new StableAgentRequestHistory();
    left.reset(canonical, "task");
    right.reset(canonical, "task");
    List<AgentMessage> a = left.prepare(canonical, Collections.emptyList(), "task").messages;
    List<AgentMessage> b = right.prepare(canonical, Collections.emptyList(), "task").messages;

    assertEquals(encodeAll(a), encodeAll(b));
    String memory = a.get(1).content();
    assertTrue(memory.contains("/p/index.html"));
    assertTrue(memory.contains("r2"));
    assertTrue(memory.contains("search_replace"));
    assertFalse(memory.contains("old\""));
  }

  @Test
  public void toolSchemaCountsTowardThe258kInputBudget() {
    List<AgentMessage> canonical = largeHistory(19, 10_000);
    ToolSpec largeTool = new ToolSpec(
        "large", repeat('规', 20_000), map("type", "object", "description", repeat('格', 10_000)));
    StableAgentRequestHistory history = new StableAgentRequestHistory();
    history.reset(canonical, "task");

    StableAgentRequestHistory.Projection result = history.prepare(
        canonical, Collections.singletonList(largeTool), "task");

    assertTrue(result.usage.toolTokens() > 25_000L);
    assertTrue(result.usage.inputTokens() <= StableAgentRequestHistory.MAX_INPUT_TOKENS);
    assertTrue(!"append_only".equals(result.usage.projectionMode()));
  }

  private static List<AgentMessage> largeHistory(int messages, int chars) {
    List<AgentMessage> result = new ArrayList<>();
    result.add(AgentMessage.system("system"));
    result.add(AgentMessage.user("task"));
    for (int index = 0; index < messages; index++) {
      result.add(AgentMessage.assistant(repeat('中', chars), Collections.emptyList()));
    }
    return result;
  }

  private static void appendWrite(List<AgentMessage> target, String id, String content) {
    ToolArguments arguments = new ToolArguments(map(
        "path", "/p/index.html",
        "replacements", Collections.singletonList(map("old", "old", "new", content))));
    target.add(AgentMessage.assistant("", Collections.singletonList(
        new AgentToolCall(id, "search_replace", arguments))));
    target.add(AgentMessage.tool(id, "search_replace", ToolResultCodec.toJson(
        ToolResult.success(map("path", "/p/index.html", "revision", id, "changed", true)))));
  }

  private static void appendBrowser(List<AgentMessage> target, String id, boolean passed) {
    target.add(AgentMessage.assistant("", Collections.singletonList(
        new AgentToolCall(id, "browser_test", new ToolArguments(map("goal", "verify"))))));
    target.add(AgentMessage.tool(id, "browser_test", ToolResultCodec.toJson(
        ToolResult.success(map("passed", passed, "failure_kind", passed ? "none" : "product")))));
  }

  private static List<String> encodeAll(List<AgentMessage> messages) {
    List<String> result = new ArrayList<>();
    for (AgentMessage message : messages) result.add(encode(message));
    return result;
  }

  private static String encode(AgentMessage message) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("role", message.role().name());
    value.put("content", message.content());
    value.put("name", message.name());
    value.put("tool_call_id", message.toolCallId());
    List<Object> calls = new ArrayList<>();
    for (AgentToolCall call : message.toolCalls()) {
      calls.add(map("id", call.id(), "name", call.name(), "arguments", call.arguments().asMap()));
    }
    value.put("tool_calls", calls);
    return GSON.toJson(value);
  }

  private static String repeat(char value, int count) {
    char[] result = new char[count];
    java.util.Arrays.fill(result, value);
    return new String(result);
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index + 1 < values.length; index += 2) {
      result.put(String.valueOf(values[index]), values[index + 1]);
    }
    return result;
  }
}
