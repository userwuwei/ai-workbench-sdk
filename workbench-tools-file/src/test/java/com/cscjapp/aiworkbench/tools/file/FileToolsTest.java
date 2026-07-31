package com.cscjapp.aiworkbench.tools.file;

import static org.junit.Assert.*;
import com.cscjapp.aiworkbench.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

public class FileToolsTest {
  private File root;
  private LocalWorkspaceAccess ws;

  @Before
  public void setUp() throws Exception {
    root = Files.createTempDirectory("aiw").toFile();
    ws = new LocalWorkspaceAccess("x", root);
  }

  @After
  public void tearDown() {
    delete(root);
  }

  @Test
  public void uniqueNamePreservesExtension() throws Exception {
    ws.writeAtomic("index.html", "a", false);
    ws.writeAtomic("index-1.html", "b", false);
    assertEquals("index-2.html", new ExistingFileConflictPolicy(ws).uniquePath("index.html"));
  }

  @Test
  public void safeReplacementKeepsCompleteNewContent() throws Exception {
    ws.writeAtomic("index.html", "old", false);
    ToolResult result =
        new CreateFileTool(ws)
            .run(
                new ToolArguments()
                    .with("path", "index.html")
                    .with("content", "complete-new-content")
                    .with("overwrite", true)
                    .with("__conflict_resolution", "overwrite"));
    assertEquals("complete-new-content", ws.read("index.html"));
    assertEquals(Boolean.FALSE, result.data().get("created"));
    assertEquals(Boolean.TRUE, result.data().get("overwritten"));
  }

  @Test
  public void createFileSchemaDoesNotExposeOverwrite() {
    ToolSpec spec = new CreateFileTool(ws).spec();
    Map<?, ?> properties = (Map<?, ?>) spec.inputSchema().get("properties");
    assertTrue(spec.description().contains("尚不存在"));
    assertTrue(spec.strictSchema());
    assertEquals(Boolean.FALSE, spec.inputSchema().get("additionalProperties"));
    assertEquals(Arrays.asList("path", "content", "file_role"), spec.inputSchema().get("required"));
    assertFalse(properties.containsKey("overwrite"));
  }

  @Test
  public void searchReplaceAppliesMultipleEditsAtomically() throws Exception {
    ws.writeAtomic("main.txt", "alpha\nbeta\ngamma\n", false);
    List<Map<String, Object>> replacements = new ArrayList<>();
    replacements.add(replacement("alpha", "one"));
    replacements.add(replacement("gamma", "three"));

    ToolResult result =
        new SearchReplaceTool(ws)
            .run(new ToolArguments().with("path", "main.txt").with("replacements", replacements));

    assertEquals("one\nbeta\nthree\n", ws.read("main.txt"));
    assertEquals(2, result.data().get("replacements"));
  }

  @Test
  public void searchReplacePrecheckFailureDoesNotWritePartialBatch() throws Exception {
    ws.writeAtomic("main.txt", "alpha\nbeta\ngamma\n", false);
    List<Map<String, Object>> replacements = new ArrayList<>();
    replacements.add(replacement("alpha", "one"));
    replacements.add(replacement("missing", "three"));

    try {
      new SearchReplaceTool(ws)
          .run(new ToolArguments().with("path", "main.txt").with("replacements", replacements));
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("search_match_count"));
    }
    assertEquals("alpha\nbeta\ngamma\n", ws.read("main.txt"));
  }

  @Test
  public void searchReplaceSchemaDeclaresExistingFileAndBatchContract() {
    ToolSpec spec = new SearchReplaceTool(ws).spec();
    Map<?, ?> properties = (Map<?, ?>) spec.inputSchema().get("properties");
    assertTrue(spec.description().contains("已有文件"));
    assertTrue(spec.description().contains("唯一编辑工具"));
    assertTrue(spec.strictSchema());
    assertEquals(Boolean.FALSE, spec.inputSchema().get("additionalProperties"));
    assertEquals(Arrays.asList("path", "replacements"), spec.inputSchema().get("required"));
    assertTrue(properties.containsKey("replacements"));
    assertFalse(properties.containsKey("old"));
    assertFalse(properties.containsKey("new"));
    assertFalse(properties.containsKey("expected_matches"));

    Map<?, ?> replacements = (Map<?, ?>) properties.get("replacements");
    Map<?, ?> item = (Map<?, ?>) replacements.get("items");
    Map<?, ?> itemProperties = (Map<?, ?>) item.get("properties");
    assertEquals(Boolean.FALSE, item.get("additionalProperties"));
    assertEquals(Arrays.asList("old", "new"), item.get("required"));
    assertEquals(new LinkedHashSet<>(Arrays.asList("old", "new")),
        new LinkedHashSet<>(itemProperties.keySet()));
  }

  @Test
  public void searchReplaceRejectsRemovedTopLevelShapeWithoutWriting() throws Exception {
    ws.writeAtomic("main.txt", "before", false);

    try {
      new SearchReplaceTool(ws).run(
          new ToolArguments()
              .with("path", "main.txt")
              .with("old", "before")
              .with("new", "after"));
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("replacements_required", expected.getMessage());
    }

    assertEquals("before", ws.read("main.txt"));
  }

  @Test
  public void readFileSupportsBoundedWindowAndSymbolRecovery() throws Exception {
    StringBuilder content = new StringBuilder();
    for (int index = 1; index <= 120; index++) {
      content.append(index == 70 ? "function resizeCanvas() {}" : "line-" + index).append('\n');
    }
    ws.writeAtomic("main.txt", content.toString(), false);
    ReadFileTool tool = new ReadFileTool(ws);

    ToolResult full = tool.run(new ToolArguments().with("path", "main.txt"));
    assertEquals("full_file", full.data().get("mode"));
    assertEquals(Boolean.TRUE, full.data().get("full_file"));
    assertTrue(String.valueOf(full.data().get("revision")).matches("[0-9a-f]{64}"));
    assertEquals(content.toString(), full.data().get("content"));

    ToolResult window =
        tool.run(
            new ToolArguments()
                .with("path", "main.txt")
                .with("start_line", 10)
                .with("end_line", 20));
    assertEquals(10, window.data().get("start_line"));
    assertEquals(20, window.data().get("end_line"));
    assertEquals("range", window.data().get("mode"));
    assertEquals(Boolean.FALSE, window.data().get("full_file"));
    assertEquals(11, String.valueOf(window.data().get("content")).split("\\n", -1).length);

    ToolResult symbol =
        tool.run(
            new ToolArguments()
                .with("path", "main.txt")
                .with("target_function", "resizeCanvas"));
    assertTrue(String.valueOf(symbol.data().get("content")).contains("resizeCanvas"));
    assertTrue(
        String.valueOf(symbol.data().get("content")).split("\\n", -1).length <= 80);

    try {
      tool.run(
          new ToolArguments()
              .with("path", "main.txt")
              .with("start_line", 1)
              .with("end_line", 81));
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("invalid_read_window", expected.getMessage());
    }
  }

  @Test
  public void rejectsTraversal() throws Exception {
    try {
      ws.resolveSafely("../escape.txt");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("outside"));
    }
  }

  private static void delete(File f) {
    if (f == null) return;
    if (f.isDirectory()) {
      File[] c = f.listFiles();
      if (c != null) for (File x : c) delete(x);
    }
    f.delete();
  }

  private static Map<String, Object> replacement(String oldText, String newText) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("old", oldText);
    item.put("new", newText);
    return item;
  }
}
