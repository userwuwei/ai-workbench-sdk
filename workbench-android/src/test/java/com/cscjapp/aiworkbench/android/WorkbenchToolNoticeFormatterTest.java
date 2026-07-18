package com.cscjapp.aiworkbench.android;

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

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      result.put(String.valueOf(values[i]), values[i + 1]);
    }
    return result;
  }
}
