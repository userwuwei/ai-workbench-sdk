package com.cscjapp.aiworkbench.tools.file;

import static org.junit.Assert.*;
import com.cscjapp.aiworkbench.api.*;
import java.io.*;
import java.nio.file.*;
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
}
