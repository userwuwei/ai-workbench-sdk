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
    assertTrue(properties.containsKey("replacements"));
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
    item.put("expected_matches", 1);
    return item;
  }
}
