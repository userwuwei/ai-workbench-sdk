package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;

public final class ReadFileTool extends AbstractFileTool {
  public ReadFileTool(WorkspaceAccess w) {
    super(w);
  }

  public ToolSpec spec() {
    return new ToolSpec(
        "read_file",
        "读取文本文件；精确编辑恢复时使用符号定位或不超过 80 行的短窗口",
        ToolSchemas.object(
            new String[][] {
              {"path", "string", "相对路径"},
              {"start_line", "integer", "可选，1-based 起始行"},
              {"end_line", "integer", "可选，1-based 结束行"},
              {"target_function", "string", "可选，函数符号"},
              {"target_class", "string", "可选，类符号"},
              {"target_method", "string", "可选，方法符号"}
            },
            "path"));
  }

  ToolResult run(ToolArguments a) throws Exception {
    String p = a.getString("path", "");
    String source = workspace.read(p);
    String[] lines = source.split("\\n", -1);
    int start = a.getInt("start_line", -1);
    int end = a.getInt("end_line", -1);
    String symbol = firstNonEmpty(
        a.getString("target_function", ""),
        a.getString("target_class", ""),
        a.getString("target_method", ""));
    if (!symbol.isEmpty()) {
      int hit = findLine(lines, symbol);
      if (hit < 0) throw new IllegalArgumentException("read_symbol_not_found");
      start = Math.max(1, hit + 1 - 20);
      end = Math.min(lines.length, start + 79);
    }
    boolean windowed = start > 0 || end > 0;
    if (windowed && (start <= 0 || end < start || end - start + 1 > 80)) {
      throw new IllegalArgumentException("invalid_read_window");
    }
    String content = windowed ? join(lines, start - 1, Math.min(end, lines.length)) : source;
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("path", p);
    d.put("content", content);
    d.put("total_lines", lines.length);
    if (windowed) {
      d.put("start_line", start);
      d.put("end_line", Math.min(end, lines.length));
      d.put("truncated", start > 1 || end < lines.length);
    }
    return ToolResult.success(d);
  }

  private static int findLine(String[] lines, String symbol) {
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].contains(symbol)) return index;
    }
    return -1;
  }

  private static String join(String[] lines, int start, int endExclusive) {
    StringBuilder result = new StringBuilder();
    for (int index = start; index < endExclusive; index++) {
      if (index > start) result.append('\n');
      result.append(lines[index]);
    }
    return result.toString();
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
    return "";
  }
}
