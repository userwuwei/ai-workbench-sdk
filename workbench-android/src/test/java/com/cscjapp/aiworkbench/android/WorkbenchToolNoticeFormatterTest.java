package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class WorkbenchToolNoticeFormatterTest {
  @Test
  public void formatsReferenceReadAndWriteNoticesWithRelativePaths() {
    ToolArguments read = new ToolArguments(map("path", "/project/src/index.html", "start_line", 4, "end_line", 8));
    String readNotice = WorkbenchToolNoticeFormatter.build(
        "read_file", read, ToolResult.success(map("start_line", 4, "end_line", 8)), "/project");
    assertTrue(readNotice.contains("已读取代码上下文"));
    assertTrue(readNotice.contains("文件：src/index.html"));
    assertTrue(readNotice.contains("范围：4-8 行"));

    String writeNotice = WorkbenchToolNoticeFormatter.build(
        "search_replace", new ToolArguments(map("path", "/project/src/app.js")),
        ToolResult.success(map("applied_count", 2, "total_lines", 90)), "/project");
    assertTrue(writeNotice.contains("已完成 2 项操作"));
    assertTrue(writeNotice.contains("编辑文件：src/app.js"));
  }

  @Test
  public void exposesBrowserLayoutFailureEvidenceInTheVisibleNotice() {
    Map<String, Object> data = map(
        "passed", false,
        "status", "error",
        "failure_reason", "horizontal_overflow",
        "steps", Arrays.asList(map("status", "error")));
    String notice = WorkbenchToolNoticeFormatter.build(
        "browser_test", ToolArguments.empty(), ToolResult.success(data), "/project");
    assertTrue(notice.contains("浏览器测试失败"));
    assertTrue(notice.contains("horizontal_overflow"));
  }

  @Test
  public void highRiskSearchReplaceSuccessAnnouncesRequiredVerification() {
    String notice = WorkbenchToolNoticeFormatter.build(
        "search_replace",
        new ToolArguments(map("path", "/project/src/app.js")),
        ToolResult.success(map(
            "applied_count", 1,
            "risk_level", "high",
            "requires_verification", true)),
        "/project");

    assertTrue(notice.contains("已完成 1 项操作"));
    assertTrue(notice.contains("本次涉及较大连续代码段"));
    assertTrue(notice.contains("接下来将执行项目验证"));
  }

  @Test
  public void invalidToolJsonDoesNotInventAFileFailure() {
    ToolResult result = ToolResult.error(
        "invalid_tool_arguments",
        "工具参数 JSON 非法：非法转义 \\\\U（位置 12）",
        true,
        Collections.singletonMap("error_offset", 12));

    String notice = WorkbenchToolNoticeFormatter.build(
        "create_file", ToolArguments.empty(), result, "/project");

    assertTrue(notice.contains("工具参数 JSON 非法"));
    assertTrue(notice.contains("工具：create_file"));
    assertTrue(notice.contains("非法转义"));
    assertFalse(notice.contains("未知文件"));
    assertFalse(notice.contains("path required"));
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      result.put(String.valueOf(values[i]), values[i + 1]);
    }
    return result;
  }
}
