package com.cscjapp.aiworkbench.android;

import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolResult;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Language-neutral port of the reference workbench's human-readable tool notices. */
final class WorkbenchToolNoticeFormatter {
  private WorkbenchToolNoticeFormatter() {}

  static String build(String tool, ToolArguments args, ToolResult result, String workspaceId) {
    Map<String, Object> data = result == null ? Collections.emptyMap() : result.data();
    boolean success = result != null && result.isSuccess();
    if (result != null && "invalid_tool_arguments".equals(result.errorCode())) {
      StringBuilder invalid = new StringBuilder("工具参数 JSON 非法");
      if (!empty(tool)) invalid.append("\n工具：").append(tool);
      if (!empty(result.message())) invalid.append("\n原因：").append(result.message());
      return invalid.toString();
    }
    String path = displayPath(args == null ? "" : args.getString("path",
        args.getString("entry_path", "")), workspaceId);
    if ("list_dir".equals(tool)) {
      return (success ? "已读取目录结构" : "读取目录结构失败") + "\n目录：" + path
          + countLine(data, "entries", "\n返回：", " 个条目");
    }
    if ("read_file".equals(tool)) return readFile(args, data, success, path);
    if ("read_file_batch".equals(tool)) {
      int count = number(data, "items_count", listSize(data.get("items")));
      return (success ? "已批量读取代码片段" : "批量读取代码片段失败")
          + "\n文件：" + path + (count >= 0 ? "\n已读目标：" + count + " 个" : "");
    }
    if ("read_plan".equals(tool)) {
      StringBuilder value = new StringBuilder(success ? "已按计划联读相关代码" : "联读计划执行失败");
      value.append("\n文件：").append(path);
      String goal = string(data.get("goal"));
      if (!goal.isEmpty()) value.append("\n目标：").append(goal);
      int count = listSize(data.get("applied_targets"));
      if (count > 0) value.append("\n计划已读：").append(count).append(" 个目标");
      return value.toString();
    }
    if ("create_file".equals(tool)) {
      String resolved = displayPath(first(data, "resolved_path", "path", "requested_path"), workspaceId);
      if ("未知文件".equals(resolved)) resolved = path;
      StringBuilder value = new StringBuilder(success ? "已应用：新建文件并写入内容" : "执行失败：新建文件并写入内容");
      value.append("\n文件：").append(resolved);
      int lines = number(data, "new_lines", -1);
      if (lines >= 0) value.append("\n写入：").append(lines).append(" 行");
      String resolution = string(data.get("conflict_resolution"));
      if ("create_new".equals(resolution)) value.append("\n已按用户选择保存到新路径");
      else if ("overwrite".equals(resolution)) value.append("\n已按用户选择覆盖原文件");
      if (!success) appendError(value, result);
      return value.toString();
    }
    if ("search_replace".equals(tool)) return searchReplace(result, data, success, path);
    if ("rewrite".equals(tool)) return rewrite(result, data, success, path);
    if ("syntax_check".equals(tool)) return syntax(result, data, success, path, workspaceId);
    if ("browser_test".equals(tool)) return browser(result, data, success);
    StringBuilder generic = new StringBuilder("已执行 ").append(empty(tool) ? "工具动作" : tool);
    if (!"未知文件".equals(path)) generic.append("，目标：").append(path);
    if (success) generic.append("，执行成功");
    else generic.append("，执行失败：").append(message(result, "工具执行失败"));
    return generic.append('。').toString();
  }

  private static String readFile(ToolArguments args, Map<String, Object> data,
      boolean success, String path) {
    StringBuilder value = new StringBuilder(success ? "已读取代码上下文" : "读取代码上下文失败");
    value.append("\n文件：").append(path);
    String target = args == null ? "" : args.getString("target_function", "");
    int start = args == null ? -1 : args.getInt("start_line", -1);
    int end = args == null ? -1 : args.getInt("end_line", start);
    if (!target.isEmpty()) value.append("\n函数：").append(target);
    else if (start > 0) value.append("\n范围：").append(start).append('-').append(Math.max(start, end)).append(" 行");
    if ("summary".equals(string(data.get("mode")))) {
      int functions = listSize(data.get("functions"));
      value.append("\n结果：返回结构摘要");
      if (functions > 0) value.append("，识别函数 ").append(functions).append(" 个");
    } else {
      int resultStart = number(data, "start_line", -1);
      int resultEnd = number(data, "end_line", resultStart);
      int lines = number(data, "lines_count", -1);
      if (resultStart > 0) value.append("\n结果：返回 ").append(resultStart).append('-')
          .append(Math.max(resultStart, resultEnd)).append(" 行");
      else if (lines >= 0) value.append("\n结果：返回 ").append(lines).append(" 行");
    }
    return value.toString();
  }

  private static String searchReplace(ToolResult result, Map<String, Object> data,
      boolean success, String path) {
    if (!success) return "执行失败：search_replace\n文件：" + path
        + "\n原因：" + message(result, "工具执行失败");
    int applied = number(data, "applied_count", -1);
    int noChange = number(data, "no_change_count", 0);
    boolean unchanged = Boolean.TRUE.equals(data.get("no_change"));
    StringBuilder value = new StringBuilder();
    if (unchanged && applied == 0) value.append("未修改：search_replace old/new 相同");
    else if (applied >= 0) {
      value.append("已完成 ").append(applied).append(" 项操作");
      if (noChange > 0) value.append("，跳过 ").append(noChange).append(" 项无变化替换");
    } else value.append("已完成：search_replace");
    value.append("\n编辑文件：").append(path);
    int total = number(data, "total_lines", -1);
    if (total >= 0) value.append("\n当前文件：").append(total).append(" 行");
    return value.toString();
  }

  private static String rewrite(ToolResult result, Map<String, Object> data,
      boolean success, String path) {
    StringBuilder value = new StringBuilder(success ? "已应用：结构单元重写" : "执行失败：结构单元重写");
    value.append("\n文件：").append(path);
    if (!success) { appendError(value, result); return value.toString(); }
    int applied = number(data, "applied_count", -1);
    int failed = number(data, "failed_count", 0);
    if (applied >= 0) value.append("\n已重写：").append(applied).append(" 个结构单元");
    if (failed > 0) value.append("\n失败：").append(failed).append(" 个结构单元");
    if (Boolean.TRUE.equals(data.get("size_changed"))) value.append("\n说明：文件行数已变化；后续会按最新结构重新定位");
    return value.toString();
  }

  private static String syntax(ToolResult result, Map<String, Object> data, boolean success,
      String path, String workspaceId) {
    StringBuilder value = new StringBuilder(success ? "已完成语法校验" : "语法校验执行失败");
    value.append("\n入口文件：").append(path);
    if (!success) { appendError(value, result); return value.toString(); }
    if (data.containsKey("passed")) value.append(bool(data.get("passed")) ? "\n结果：通过" : "\n结果：未通过");
    String main = first(data, "main_file");
    if (!main.isEmpty()) value.append("\n实际校验：").append(displayPath(main, workspaceId));
    String error = first(data, "error", "failure_reason");
    if (!error.isEmpty()) value.append("\n首条错误：").append(firstLine(error));
    return value.toString();
  }

  private static String browser(ToolResult result, Map<String, Object> data, boolean success) {
    String status = string(data.get("status"));
    boolean passed = bool(data.get("passed"));
    StringBuilder value = new StringBuilder();
    if ("blocked".equals(status)) value.append("浏览器测试阻塞");
    else if (success && passed) value.append("浏览器测试通过");
    else value.append("浏览器测试失败");
    int steps = listSize(data.get("steps"));
    if (steps > 0 && passed) value.append("\n完成 ").append(steps).append(" 个页面操作");
    String detail = first(data, "failure_reason", "title", "final_url", "opened_url");
    if (!detail.isEmpty()) value.append("\n").append(shortText(detail, 58));
    if (!success) value.append("\n").append(message(result, "浏览器测试失败"));
    return value.toString();
  }

  private static String displayPath(String path, String root) {
    if (empty(path)) return "未知文件";
    try {
      String canonical = new File(path).getCanonicalPath();
      String rootPath = empty(root) ? "" : new File(root).getCanonicalPath();
      if (!rootPath.isEmpty() && canonical.startsWith(rootPath + File.separator)) {
        return canonical.substring(rootPath.length() + 1);
      }
    } catch (Exception ignored) {}
    return path;
  }

  private static String countLine(Map<String, Object> data, String key, String prefix, String suffix) {
    int count = listSize(data.get(key));
    return count >= 0 ? prefix + count + suffix : "";
  }

  private static int listSize(Object value) { return value instanceof List ? ((List<?>) value).size() : -1; }
  private static int number(Map<String, Object> data, String key, int fallback) {
    Object value = data.get(key);
    return value instanceof Number ? ((Number) value).intValue() : fallback;
  }
  private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
  private static String first(Map<?, ?> data, String... keys) {
    for (String key : keys) { String value = string(data.get(key)); if (!value.isEmpty()) return value; }
    return "";
  }
  private static String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
  private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
  private static String message(ToolResult result, String fallback) {
    return result == null || empty(result.message()) ? fallback : result.message();
  }
  private static void appendError(StringBuilder value, ToolResult result) {
    value.append("\n原因：").append(message(result, "工具执行失败"));
  }
  private static String firstLine(String value) {
    int line = value.indexOf('\n');
    return (line < 0 ? value : value.substring(0, line)).trim();
  }
  private static String shortText(String value, int max) {
    String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
    return compact.length() <= max ? compact : compact.substring(0, max - 1) + "…";
  }
}
