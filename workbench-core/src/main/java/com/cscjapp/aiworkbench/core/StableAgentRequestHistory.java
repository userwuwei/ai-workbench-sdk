package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.ToolSpec;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Non-destructive, append-only model view with bounded high-water compaction epochs. */
final class StableAgentRequestHistory {
  static final long MAX_INPUT_TOKENS = 258_000L;
  static final long SOFT_COMPACT_TOKENS = 201_240L;
  static final long HARD_COMPACT_TOKENS = 227_040L;
  static final long SOFT_TARGET_TOKENS = 154_800L;
  static final long HARD_TARGET_TOKENS = 129_000L;
  static final int SUMMARY_MAX_CHARS = 24_000;
  static final int SOFT_RECENT_MESSAGES = 20;
  static final int HARD_RECENT_MESSAGES = 12;
  static final String MEMORY_MARKER = "【SDK上下文压缩记忆】";

  private static final Gson GSON = new Gson();

  private final List<AgentMessage> frozen = new ArrayList<>();
  private int sourceCount;
  private int lastCompactedSourceCount = -1;
  private String demand = "";
  private String mode = "append_only";
  private ModelUsage providerUsage = ModelUsage.UNKNOWN;
  private RequestContextUsage latestUsage;

  synchronized void reset(List<AgentMessage> canonical, String currentDemand) {
    frozen.clear();
    List<AgentMessage> safe = AgentHistory.sanitize(canonical);
    frozen.addAll(AgentHistoryRequestProjection.project(safe));
    sourceCount = safe.size();
    lastCompactedSourceCount = -1;
    demand = currentDemand == null ? "" : currentDemand;
    mode = "append_only";
    providerUsage = ModelUsage.UNKNOWN;
    latestUsage = null;
  }

  synchronized Projection prepare(
      List<AgentMessage> canonical, List<ToolSpec> tools, String currentDemand) {
    if (currentDemand != null && !currentDemand.isEmpty()) demand = currentDemand;
    appendNewTransactions(canonical);
    long before = AgentTokenEstimator.total(frozen, tools);
    if (sourceCount != lastCompactedSourceCount && before >= HARD_COMPACT_TOKENS) {
      compact(canonical, tools, true);
    } else if (sourceCount != lastCompactedSourceCount && before >= SOFT_COMPACT_TOKENS) {
      compact(canonical, tools, false);
      if (AgentTokenEstimator.total(frozen, tools) >= SOFT_COMPACT_TOKENS) {
        compact(canonical, tools, true);
      }
    }
    if (AgentTokenEstimator.total(frozen, tools) > MAX_INPUT_TOKENS) {
      enforceMaximum(tools);
    }
    if (AgentTokenEstimator.total(frozen, tools) > MAX_INPUT_TOKENS) {
      throw new IllegalStateException("model_request_context_exceeds_258k");
    }
    long messageTokens = AgentTokenEstimator.messages(frozen);
    long toolTokens = AgentTokenEstimator.tools(tools);
    latestUsage =
        new RequestContextUsage(
            messageTokens + toolTokens + 64L,
            messageTokens,
            toolTokens,
            MAX_INPUT_TOKENS,
            mode,
            providerUsage);
    return new Projection(
        Collections.unmodifiableList(new ArrayList<>(frozen)), latestUsage);
  }

  synchronized void recordUsage(ModelUsage usage) {
    providerUsage = usage == null ? ModelUsage.UNKNOWN : usage;
    if (latestUsage != null) {
      latestUsage =
          new RequestContextUsage(
              latestUsage.inputTokens(),
              latestUsage.messageTokens(),
              latestUsage.toolTokens(),
              latestUsage.maximumInputTokens(),
              latestUsage.projectionMode(),
              providerUsage);
    }
  }

  synchronized RequestContextUsage latestUsage() {
    return latestUsage;
  }

  synchronized List<AgentMessage> snapshotForTests() {
    return Collections.unmodifiableList(new ArrayList<>(frozen));
  }

  private void appendNewTransactions(List<AgentMessage> canonical) {
    List<AgentMessage> safe = AgentHistory.sanitize(canonical);
    if (sourceCount > safe.size() || frozen.isEmpty()) {
      reset(safe, demand);
      return;
    }
    if (sourceCount == safe.size()) return;
    List<AgentMessage> projected = AgentHistoryRequestProjection.project(safe);
    frozen.addAll(projected.subList(sourceCount, projected.size()));
    sourceCount = safe.size();
  }

  private void compact(List<AgentMessage> canonical, List<ToolSpec> tools, boolean hard) {
    List<AgentMessage> safe = AgentHistory.sanitize(canonical);
    List<AgentMessage> projected = AgentHistoryRequestProjection.project(safe);
    int demandIndex = latestDemandIndex(safe, demand);
    AgentMessage system = firstSystem(safe);
    AgentMessage memory = AgentMessage.system(buildMemory(safe, hard));
    AgentMessage currentDemand = AgentMessage.user(demand);
    int desiredMessages = hard ? HARD_RECENT_MESSAGES : SOFT_RECENT_MESSAGES;
    long target = hard ? HARD_TARGET_TOKENS : SOFT_TARGET_TOKENS;
    List<Group> groups = groups(safe);
    int firstTailGroup = suffixStart(groups, desiredMessages, demandIndex);
    List<AgentMessage> base = new ArrayList<>();
    if (system != null) base.add(system);
    base.add(memory);
    if (!demand.isEmpty()) base.add(currentDemand);
    List<ProjectedGroup> tailGroups =
        projectedGroups(
            projected, groups, firstTailGroup, demandIndex, protectedGroups(safe, groups));
    frozen.clear();
    frozen.addAll(base);
    appendTail(tailGroups, 0);
    while (AgentTokenEstimator.total(frozen, tools) > target && tailGroups.size() > 1) {
      int removable = firstRemovable(tailGroups);
      if (removable < 0) break;
      tailGroups.remove(removable);
      frozen.clear();
      frozen.addAll(base);
      appendTail(tailGroups, 0);
    }
    sourceCount = safe.size();
    lastCompactedSourceCount = sourceCount;
    mode = hard ? "hard_88" : "soft_78";
  }

  private void enforceMaximum(List<ToolSpec> tools) {
    List<Group> groups = groups(frozen);
    int protectedPrefix = protectedPrefixEnd(frozen);
    while (AgentTokenEstimator.total(frozen, tools) > MAX_INPUT_TOKENS) {
      Group removable = null;
      for (Group group : groups) {
        if (group.start >= protectedPrefix) {
          removable = group;
          break;
        }
      }
      if (removable == null || removable.end >= frozen.size()) break;
      frozen.subList(removable.start, removable.end).clear();
      groups = groups(frozen);
    }
    mode = "hard_limit";
  }

  private void appendTail(List<ProjectedGroup> tailGroups, int start) {
    for (int index = Math.max(0, start); index < tailGroups.size(); index++) {
      frozen.addAll(tailGroups.get(index).messages);
    }
  }

  private static List<ProjectedGroup> projectedGroups(
      List<AgentMessage> projected,
      List<Group> groups,
      int start,
      int demandIndex,
      Set<Integer> protectedGroups) {
    List<ProjectedGroup> result = new ArrayList<>();
    for (int index = Math.max(0, start); index < groups.size(); index++) {
      Group group = groups.get(index);
      if (group.start == 0 && projected.get(0).role() == AgentMessage.Role.SYSTEM) continue;
      if (demandIndex >= group.start && demandIndex < group.end) continue;
      result.add(
          new ProjectedGroup(
              new ArrayList<>(projected.subList(group.start, group.end)),
              protectedGroups.contains(index)));
    }
    for (Integer index : protectedGroups) {
      if (index == null || index >= start || index < 0 || index >= groups.size()) continue;
      Group group = groups.get(index);
      if (demandIndex >= group.start && demandIndex < group.end) continue;
      int insertion = 0;
      while (insertion < result.size()
          && sourceStart(result.get(insertion), projected) < group.start) insertion++;
      result.add(
          insertion,
          new ProjectedGroup(
              new ArrayList<>(projected.subList(group.start, group.end)), true));
    }
    return result;
  }

  private static int sourceStart(ProjectedGroup group, List<AgentMessage> source) {
    return group.messages.isEmpty() ? Integer.MAX_VALUE : source.indexOf(group.messages.get(0));
  }

  private static int firstRemovable(List<ProjectedGroup> groups) {
    for (int index = 0; index < groups.size() - 1; index++) {
      if (!groups.get(index).protectedEvidence) return index;
    }
    return -1;
  }

  private static Set<Integer> protectedGroups(List<AgentMessage> messages, List<Group> groups) {
    Map<String, Integer> latest = new LinkedHashMap<>();
    for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
      Group group = groups.get(groupIndex);
      AgentMessage first = messages.get(group.start);
      if (first.role() != AgentMessage.Role.ASSISTANT || first.toolCalls().isEmpty()) continue;
      boolean failure = false;
      for (AgentToolCall call : first.toolCalls()) {
        String name = call.name();
        if ("plan_task".equals(name)) latest.put("plan", groupIndex);
        if ("read_file".equals(name) || "read_plan".equals(name)) latest.put("read", groupIndex);
        if ("create_file".equals(name) || "search_replace".equals(name) || "rewrite".equals(name)) {
          latest.put("write", groupIndex);
        }
        if (isVerification(name)) latest.put(name, groupIndex);
      }
      for (int index = group.start + 1; index < group.end; index++) {
        JsonObject root = parseObject(messages.get(index).content());
        if (root != null && isFailure(root, object(root.get("data")))) failure = true;
      }
      if (failure) latest.put("failure", groupIndex);
    }
    return new LinkedHashSet<>(latest.values());
  }

  private static int suffixStart(List<Group> groups, int desiredMessages, int demandIndex) {
    int count = 0;
    int start = groups.size();
    for (int index = groups.size() - 1; index >= 0; index--) {
      Group group = groups.get(index);
      if (demandIndex >= group.start && demandIndex < group.end) continue;
      count += group.end - group.start;
      start = index;
      if (count >= desiredMessages) break;
    }
    return start;
  }

  private static List<Group> groups(List<AgentMessage> messages) {
    List<Group> result = new ArrayList<>();
    for (int index = 0; index < messages.size(); ) {
      int end = index + 1;
      AgentMessage message = messages.get(index);
      if (message.role() == AgentMessage.Role.ASSISTANT && !message.toolCalls().isEmpty()) {
        while (end < messages.size() && messages.get(end).role() == AgentMessage.Role.TOOL) end++;
      }
      result.add(new Group(index, end));
      index = end;
    }
    return result;
  }

  private static AgentMessage firstSystem(List<AgentMessage> messages) {
    return !messages.isEmpty() && messages.get(0).role() == AgentMessage.Role.SYSTEM
        ? messages.get(0) : null;
  }

  private static int latestDemandIndex(List<AgentMessage> messages, String demand) {
    for (int index = messages.size() - 1; index >= 0; index--) {
      AgentMessage message = messages.get(index);
      if (message.role() == AgentMessage.Role.USER && message.content().equals(demand)) return index;
    }
    return -1;
  }

  private static int protectedPrefixEnd(List<AgentMessage> messages) {
    int end = 0;
    while (end < messages.size() && end < 3) {
      AgentMessage.Role role = messages.get(end).role();
      if (role != AgentMessage.Role.SYSTEM && role != AgentMessage.Role.USER) break;
      end++;
    }
    return end;
  }

  private static String buildMemory(List<AgentMessage> messages, boolean hard) {
    JsonObject memory = new JsonObject();
    memory.addProperty("kind", "continuity_only_not_edit_or_verification_evidence");
    memory.addProperty("mode", hard ? "hard" : "soft");
    JsonElement latestPlanState = null;
    JsonElement plannedFiles = null;
    String recommended = "";
    LinkedHashMap<String, JsonObject> files = new LinkedHashMap<>();
    JsonArray verification = new JsonArray();
    JsonArray failures = new JsonArray();

    for (AgentMessage message : messages) {
      if (message.role() == AgentMessage.Role.ASSISTANT) {
        for (AgentToolCall call : message.toolCalls()) {
          if ("plan_task".equals(call.name())) {
            Object raw = call.arguments() == null ? null : call.arguments().get("planned_files");
            if (raw != null) plannedFiles = bounded(GSON.toJsonTree(raw), 6000);
          }
        }
        continue;
      }
      if (message.role() != AgentMessage.Role.TOOL) continue;
      JsonObject root = parseObject(message.content());
      if (root == null) continue;
      JsonObject data = object(root.get("data"));
      if (data != null && data.has("plan_state")) {
        latestPlanState = bounded(data.get("plan_state"), 6000);
      }
      String next = firstString(data, root, "recommended_next_action");
      if (!next.isEmpty()) recommended = next;
      collectFile(files, root, data, message.name());
      if (isVerification(message.name())) {
        JsonObject item = new JsonObject();
        item.addProperty("tool", message.name());
        copy(root, item, "status", "error_code");
        copy(data, item, "passed", "failure_kind", "revision", "recommended_next_action");
        verification.add(item);
      }
      if (isFailure(root, data)) {
        JsonObject item = new JsonObject();
        item.addProperty("tool", message.name());
        copy(root, item, "status", "error_code", "message");
        copy(
            data,
            item,
            "failure_kind",
            "failure_reason",
            "reading_brief",
            "test_retry_brief",
            "recommended_retry",
            "recommended_next_action",
            "copyable_old_candidates",
            "preferred_retry_old");
        failures.add(bounded(item, 5000));
        while (failures.size() > 8) failures.remove(0);
      }
    }
    if (plannedFiles != null) memory.add("planned_files", plannedFiles);
    if (latestPlanState != null) memory.add("plan_state", latestPlanState);
    if (!files.isEmpty()) memory.add("files", GSON.toJsonTree(files.values()));
    if (verification.size() > 0) memory.add("verification", verification);
    if (failures.size() > 0) memory.add("unresolved_or_recent_failures", failures);
    if (!recommended.isEmpty()) memory.addProperty("recommended_next_action", recommended);
    String value = MEMORY_MARKER + "\n" + GSON.toJson(memory);
    return value.length() <= SUMMARY_MAX_CHARS
        ? value : value.substring(0, SUMMARY_MAX_CHARS - 1) + "…";
  }

  private static void collectFile(
      Map<String, JsonObject> files, JsonObject root, JsonObject data, String tool) {
    if (data == null) return;
    String path = string(data, "path");
    if (path.isEmpty()) path = string(data, "resolved_path");
    if (path.isEmpty()) path = string(data, "requested_path");
    if (path.isEmpty()) return;
    JsonObject fact = new JsonObject();
    fact.addProperty("path", path);
    fact.addProperty("tool", tool == null ? "" : tool);
    copy(root, fact, "status", "error_code");
    copy(data, fact, "revision", "content_hash", "operation", "total_lines", "changed");
    files.put(path, fact);
    while (files.size() > 12) files.remove(files.keySet().iterator().next());
  }

  private static boolean isVerification(String name) {
    return "syntax_check".equals(name)
        || "browser_test".equals(name)
        || "quality_review".equals(name);
  }

  private static boolean isFailure(JsonObject root, JsonObject data) {
    if (root == null) return false;
    if (root.has("status") && "error".equalsIgnoreCase(string(root, "status"))) return true;
    if (data == null) return false;
    String kind = string(data, "failure_kind");
    return (!kind.isEmpty() && !"none".equals(kind))
        || (data.has("passed") && !booleanValue(data.get("passed")));
  }

  private static JsonElement bounded(JsonElement source, int maximumChars) {
    if (source == null || source.isJsonNull()) return new JsonObject();
    String value = GSON.toJson(source);
    if (value.length() <= maximumChars) return source.deepCopy();
    JsonObject compact = new JsonObject();
    compact.addProperty("compacted", true);
    compact.addProperty("preview", value.substring(0, Math.max(0, maximumChars - 32)) + "…");
    return compact;
  }

  private static JsonObject parseObject(String value) {
    try {
      JsonElement parsed = JsonParser.parseString(value);
      return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static JsonObject object(JsonElement value) {
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static String firstString(JsonObject primary, JsonObject secondary, String key) {
    String value = string(primary, key);
    return value.isEmpty() ? string(secondary, key) : value;
  }

  private static String string(JsonObject value, String key) {
    try {
      return value != null && value.has(key) && !value.get(key).isJsonNull()
          ? value.get(key).getAsString() : "";
    } catch (RuntimeException ignored) {
      return "";
    }
  }

  private static boolean booleanValue(JsonElement value) {
    try {
      return value != null && !value.isJsonNull() && value.getAsBoolean();
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static void copy(JsonObject source, JsonObject target, String... keys) {
    if (source == null) return;
    for (String key : keys) {
      if (source.has(key) && !source.get(key).isJsonNull()) {
        target.add(key, bounded(source.get(key), 5000));
      }
    }
  }

  static final class Projection {
    final List<AgentMessage> messages;
    final RequestContextUsage usage;

    Projection(List<AgentMessage> messages, RequestContextUsage usage) {
      this.messages = messages;
      this.usage = usage;
    }
  }

  private static final class Group {
    final int start;
    final int end;

    Group(int start, int end) {
      this.start = start;
      this.end = end;
    }
  }

  private static final class ProjectedGroup {
    final List<AgentMessage> messages;
    final boolean protectedEvidence;

    ProjectedGroup(List<AgentMessage> messages, boolean protectedEvidence) {
      this.messages = messages;
      this.protectedEvidence = protectedEvidence;
    }
  }
}
