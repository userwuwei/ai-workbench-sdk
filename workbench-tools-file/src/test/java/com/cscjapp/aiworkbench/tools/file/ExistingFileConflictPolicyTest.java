package com.cscjapp.aiworkbench.tools.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolCallback;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolInvocation;
import com.cscjapp.aiworkbench.api.ToolPolicyDecision;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;
import com.cscjapp.aiworkbench.api.UserDecisionRequest;
import com.cscjapp.aiworkbench.api.UserDecisionService;
import com.cscjapp.aiworkbench.api.WorkbenchEvent;
import com.cscjapp.aiworkbench.api.WorkbenchHost;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExistingFileConflictPolicyTest {
  private File root;
  private LocalWorkspaceAccess workspace;

  @Before
  public void setUp() throws Exception {
    root = Files.createTempDirectory("aiw-conflict").toFile();
    workspace = new LocalWorkspaceAccess("test", root);
    workspace.writeAtomic("index.html", "old", false);
    workspace.writeAtomic("index-1.html", "old-1", false);
  }

  @After
  public void tearDown() {
    delete(root);
  }

  @Test
  public void createNewContinuesSameInvocationWithUniquePath() {
    ToolPolicyDecision decision = evaluate("create_new");
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision.kind());
    assertEquals("index.html", decision.arguments().getString("__requested_path", ""));
    assertEquals("index-2.html", decision.arguments().getString("path", ""));
    assertEquals("create_new", decision.arguments().getString("__conflict_resolution", ""));
    assertFalse(decision.arguments().getBoolean("overwrite", true));
  }

  @Test
  public void overwriteContinuesSameInvocationWithOriginalContentArguments() {
    ToolPolicyDecision decision = evaluate("overwrite");
    assertEquals(ToolPolicyDecision.Kind.PROCEED, decision.kind());
    assertEquals("index.html", decision.arguments().getString("path", ""));
    assertEquals("full-model-content", decision.arguments().getString("content", ""));
    assertTrue(decision.arguments().getBoolean("overwrite", false));
  }

  @Test
  public void cancellationIsNotReportedAsModelToolError() {
    AtomicReference<ToolPolicyDecision> result = new AtomicReference<>();
    new ExistingFileConflictPolicy(workspace)
        .evaluate(context("cancel"), invocation(), result::set);
    assertEquals(ToolPolicyDecision.Kind.CANCEL, result.get().kind());
    assertEquals(ToolResult.Status.CANCELLED, result.get().result().status());
  }

  private ToolPolicyDecision evaluate(String option) {
    AtomicReference<ToolPolicyDecision> result = new AtomicReference<>();
    new ExistingFileConflictPolicy(workspace).evaluate(context(option), invocation(), result::set);
    return result.get();
  }

  private ToolInvocation invocation() {
    return new ToolInvocation(
        "call-1",
        new AgentTool() {
          @Override
          public ToolSpec spec() {
            return new ToolSpec("create_file", "", Collections.singletonMap("type", "object"));
          }

          @Override
          public Cancellable execute(
              ToolContext context, ToolArguments arguments, ToolCallback callback) {
            return Cancellable.NONE;
          }
        },
        new ToolArguments()
            .with("path", "index.html")
            .with("content", "full-model-content")
            .with("file_role", "entry_source"));
  }

  private ToolContext context(String option) {
    return new ToolContext() {
      @Override
      public String sessionId() {
        return "session";
      }

      @Override
      public String workspaceId() {
        return "test";
      }

      @Override
      public java.util.concurrent.Executor backgroundExecutor() {
        return Runnable::run;
      }

      @Override
      public UserDecisionService userDecisions() {
        return (request, callback) -> {
          if ("cancel".equals(option)) callback.onCancelled();
          else callback.onDecision(option);
          return Cancellable.NONE;
        };
      }

      @Override
      public WorkbenchHost host() {
        return new WorkbenchHost() {
          public void openArtifact(String id) {}

          public void refreshArtifacts() {}

          public void handleAction(String id, ToolArguments arguments) {}

          public void onEvent(WorkbenchEvent event) {}
        };
      }
    };
  }

  private static void delete(File file) {
    if (file == null) return;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) delete(child);
    file.delete();
  }
}
