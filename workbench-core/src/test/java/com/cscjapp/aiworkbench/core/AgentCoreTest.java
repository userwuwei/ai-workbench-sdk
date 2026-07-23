package com.cscjapp.aiworkbench.core;

import static org.junit.Assert.*;
import com.cscjapp.aiworkbench.api.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

public class AgentCoreTest {
  @Test
  public void promptComposerOrdersAndBudgetsSections() {
    WorkbenchDefinition d =
        definition(
            Collections.emptyList(),
            Arrays.asList(
                c ->
                    Arrays.asList(
                        new PromptSection("b", PromptPhase.APP_RULES, 2, 3, "12345"),
                        new PromptSection("a", PromptPhase.BASE, 1, 0, "base"))));
    String value = new PromptComposer().compose(d, new PromptContext("w", "d", null));
    assertTrue(value.indexOf("## a") < value.indexOf("## b"));
    assertTrue(value.contains("123\n...[truncated]"));
  }

  @Test(expected = IllegalStateException.class)
  public void registryRejectsDuplicateNames() {
    AgentTool t = tool("x", false);
    new ToolRegistry(Arrays.asList(t, t));
  }

  @Test
  public void terminalToolValidatesAndFinishesWithoutExtraModelRound() {
    AtomicInteger rounds = new AtomicInteger();
    ModelGateway gateway =
        (req, observer) -> {
          rounds.incrementAndGet();
          observer.onComplete(
              new ModelResponse(
                  "",
                  "tool_calls",
                  Collections.singletonList(
                      new AgentToolCall(
                          "1",
                          "finalize_task",
                          new ToolArguments(Collections.singletonMap("summary", "done"))))));
          return Cancellable.NONE;
        };
    AtomicReference<String> finalText = new AtomicReference<>();
    AgentEngine e =
        new AgentEngine(
            definition(
                Collections.singletonList(tool("finalize_task", true)), Collections.emptyList()),
            gateway,
            new ModelEndpoint("http://localhost", "", "m", 0, true, false),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);
    e.submit("task", observer(finalText));
    assertEquals(1, rounds.get());
    assertEquals("done", finalText.get());
  }

  @Test
  public void modelRequestUsesRoundToolSelectionPolicy() {
    AtomicReference<List<String>> visible = new AtomicReference<>();
    ToolPolicy selectionPolicy =
        new ToolPolicy() {
          @Override
          public ToolSelection selectTools(AgentRoundContext context, List<ToolSpec> tools) {
            assertEquals(0, context.round());
            assertEquals("task", context.runContext().demand());
            return ToolSelection.onlyNames(tools, Arrays.asList("read_plan", "finalize_task"));
          }

          public boolean supports(ToolInvocation invocation) {
            return false;
          }

          public Cancellable evaluate(
              ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
            throw new AssertionError("selection-only policy must not evaluate invocations");
          }
        };
    ModelGateway gateway =
        (request, observer) -> {
          List<String> names = new ArrayList<>();
          for (ToolSpec spec : request.tools()) names.add(spec.name());
          visible.set(names);
          observer.onComplete(new ModelResponse("done", "stop", Collections.emptyList()));
          return Cancellable.NONE;
        };
    WorkbenchDefinition base =
        definition(
            Arrays.asList(
                tool("read_file", false),
                tool("read_file_batch", false),
                tool("read_plan", false),
                tool("finalize_task", false)),
            Collections.emptyList());
    WorkbenchDefinition routed =
        new WorkbenchDefinition() {
          public String id() { return base.id(); }
          public String displayName() { return base.displayName(); }
          public List<PromptContributor> promptContributors() { return base.promptContributors(); }
          public List<ContextProvider> contextProviders() { return base.contextProviders(); }
          public List<AgentTool> tools() { return base.tools(); }
          public List<ToolPolicy> toolPolicies() { return Collections.singletonList(selectionPolicy); }
          public List<TaskValidator> validators() { return base.validators(); }
          public WorkbenchHost host() { return base.host(); }
        };
    AgentEngine engine =
        new AgentEngine(
            routed,
            gateway,
            new ModelEndpoint("http://localhost", "", "m", 0, false, false),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            2);

    engine.submit("task", observer(new AtomicReference<>()));

    assertEquals(Arrays.asList("read_plan", "finalize_task"), visible.get());
  }

  @Test
  public void refreshesSystemPromptForEveryUserDemand() {
    List<String> systems = new ArrayList<>();
    ModelGateway gateway =
        (req, observer) -> {
          systems.add(req.messages().get(0).content());
          observer.onComplete(new ModelResponse("ok", "stop", Collections.emptyList()));
          return Cancellable.NONE;
        };
    WorkbenchDefinition d =
        definition(
            Collections.emptyList(),
            Collections.singletonList(
                c ->
                    Collections.singletonList(
                        new PromptSection("demand", PromptPhase.RUNTIME, 0, 100, c.demand()))));
    AgentEngine e =
        new AgentEngine(
            d,
            gateway,
            new ModelEndpoint("http://localhost", "", "m", 0, false, false),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);
    AtomicReference<String> out = new AtomicReference<>();
    e.submit("first", observer(out));
    e.submit("second", observer(out));
    assertEquals(Arrays.asList("## demand\nfirst", "## demand\nsecond"), systems);
  }

  @Test
  public void dropsIncompletePersistedNativeToolTurn() {
    AgentMessage assistant =
        AgentMessage.assistant(
            "",
            Arrays.asList(
                new AgentToolCall("a", "x", new ToolArguments()),
                new AgentToolCall("b", "y", new ToolArguments())));
    List<AgentMessage> safe =
        AgentHistory.sanitize(
            Arrays.asList(
                AgentMessage.system("s"),
                AgentMessage.user("u"),
                assistant,
                AgentMessage.tool("a", "x", "{}")));
    assertEquals(2, safe.size());
    assertEquals(AgentMessage.Role.USER, safe.get(1).role());
  }

  @Test
  public void completedTaskHistoryDropsRawFileArgumentsButKeepsOutcomeSummary() {
    String largeContent = String.join("", Collections.nCopies(2000, "generated-source-"));
    Map<String, Object> createArguments = new LinkedHashMap<>();
    createArguments.put("path", "src/main.txt");
    createArguments.put("content", largeContent);
    createArguments.put("overwrite", true);
    Map<String, Object> finalizeArguments = new LinkedHashMap<>();
    finalizeArguments.put("status", "completed");
    finalizeArguments.put("summary", "完成布局优化");
    finalizeArguments.put("changed_files", Collections.singletonList("src/main.txt"));
    finalizeArguments.put("verification", Collections.singletonList("compile_test passed"));
    List<AgentMessage> source =
        Arrays.asList(
            AgentMessage.system("system"),
            AgentMessage.user("调整布局并增强质感"),
            AgentMessage.assistant(
                "",
                Collections.singletonList(
                    new AgentToolCall("create", "create_file", new ToolArguments(createArguments)))),
            AgentMessage.tool("create", "create_file", "{\"status\":\"success\"}"),
            AgentMessage.assistant(
                "",
                Collections.singletonList(
                    new AgentToolCall("final", "finalize_task", new ToolArguments(finalizeArguments)))),
            AgentMessage.tool("final", "finalize_task", "{\"status\":\"success\"}"));

    List<AgentMessage> compacted = AgentHistory.compactCompletedTasks(source);

    assertEquals(2, compacted.size());
    assertEquals(AgentMessage.Role.SYSTEM, compacted.get(0).role());
    String summary = compacted.get(1).content();
    assertTrue(summary.startsWith(AgentHistory.COMPLETED_TASK_HISTORY_PREFIX));
    assertTrue(summary.contains("调整布局并增强质感"));
    assertTrue(summary.contains("src/main.txt"));
    assertTrue(summary.contains("compile_test passed"));
    assertFalse(summary.contains(largeContent));
    assertFalse(summary.contains("overwrite"));
    assertFalse(summary.contains("create_file"));
  }

  @Test
  public void unfinishedCurrentTaskKeepsRawToolProtocol() {
    AgentMessage call =
        AgentMessage.assistant(
            "",
            Collections.singletonList(
                new AgentToolCall(
                    "read", "read_file", new ToolArguments(Collections.singletonMap("path", "main.txt")))));
    List<AgentMessage> source =
        Arrays.asList(
            AgentMessage.system("system"),
            AgentMessage.user("task"),
            call,
            AgentMessage.tool("read", "read_file", "content"));
    assertEquals(source, AgentHistory.compactCompletedTasks(source));
  }

  @Test
  public void nativeModeRequiresRegisteredTerminalToolInsteadOfAcceptingPlainText() {
    AtomicInteger rounds = new AtomicInteger();
    ModelGateway gateway =
        (req, observer) -> {
          if (rounds.incrementAndGet() == 1)
            observer.onComplete(new ModelResponse("plain", "stop", Collections.emptyList()));
          else
            observer.onComplete(
                new ModelResponse(
                    "",
                    "tool_calls",
                    Collections.singletonList(
                        new AgentToolCall(
                            "f",
                            "finalize_task",
                            new ToolArguments(Collections.singletonMap("summary", "final"))))));
          return Cancellable.NONE;
        };
    AtomicReference<String> out = new AtomicReference<>();
    AgentEngine engine =
        new AgentEngine(
            definition(
                Collections.singletonList(tool("finalize_task", true)), Collections.emptyList()),
            gateway,
            new ModelEndpoint("http://localhost", "", "m", 0, true, false),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);
    engine.submit("task", observer(out));
    assertEquals(2, rounds.get());
    assertEquals("final", out.get());
  }

  @Test
  public void cancelledRunCannotCompleteAfterANewSubmissionStarts() {
    List<ModelStreamObserver> pending = new ArrayList<>();
    ModelGateway gateway =
        (request, observer) -> {
          pending.add(observer);
          return Cancellable.NONE;
        };
    AgentEngine engine =
        new AgentEngine(
            definition(Collections.emptyList(), Collections.emptyList()),
            gateway,
            new ModelEndpoint("http://localhost", "", "m", 0, false, false),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);
    List<String> finals = new ArrayList<>();
    AgentObserver observer =
        new AgentObserver() {
          public void onState(String state) {}

          public void onDelta(String content, String reasoning) {}

          public void onToolStarted(String id, String name, ToolArguments arguments) {}

          public void onToolProgress(
              String id, String stage, long current, long total, String message) {}

          public void onToolCompleted(String id, String name, ToolResult result) {}

          public void onValidation(ValidationResult result) {}

          public void onFinal(String content) {
            finals.add(content);
          }

          public void onError(Throwable error) {
            throw new AssertionError(error);
          }
        };

    engine.submit("old", observer);
    engine.cancel();
    engine.submit("new", observer);
    assertEquals(2, pending.size());
    pending.get(1).onComplete(new ModelResponse("new-result", "stop", Collections.emptyList()));
    pending.get(0).onComplete(new ModelResponse("stale-result", "stop", Collections.emptyList()));

    assertEquals(Collections.singletonList("new-result"), finals);
  }

  @Test
  public void forwardsStructuredStreamDeltasWithoutDuplicatingLegacyCallback() {
    AtomicReference<ModelStreamDelta> received = new AtomicReference<>();
    AtomicInteger legacyCallbacks = new AtomicInteger();
    ModelGateway gateway =
        (request, observer) -> {
          observer.onStreamDelta(
              new ModelStreamDelta(
                  "",
                  "thinking",
                  Collections.singletonList(
                      new ToolCallStreamDelta(0, "call-1", "create_file", "{\"path\":"))));
          observer.onComplete(new ModelResponse("done", "stop", Collections.emptyList()));
          return Cancellable.NONE;
        };
    AgentEngine engine =
        new AgentEngine(
            definition(Collections.emptyList(), Collections.emptyList()),
            gateway,
            new ModelEndpoint("http://localhost", "", "m", 0, false, false),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);
    AtomicReference<String> finalText = new AtomicReference<>();
    AgentObserver observer =
        new AgentObserver() {
          public void onState(String state) {}

          public void onDelta(String content, String reasoning) {
            legacyCallbacks.incrementAndGet();
          }

          public void onStreamDelta(ModelStreamDelta delta) {
            received.set(delta);
          }

          public void onToolStarted(String id, String name, ToolArguments arguments) {}

          public void onToolProgress(
              String id, String stage, long current, long total, String message) {}

          public void onToolCompleted(String id, String name, ToolResult result) {}

          public void onValidation(ValidationResult result) {}

          public void onFinal(String content) {
            finalText.set(content);
          }

          public void onError(Throwable error) {
            throw new AssertionError(error);
          }
        };

    engine.submit("task", observer);

    assertNotNull(received.get());
    assertEquals("thinking", received.get().reasoning());
    assertEquals(1, received.get().toolCalls().size());
    assertEquals("create_file", received.get().toolCalls().get(0).name());
    assertEquals(0, legacyCallbacks.get());
    assertEquals("done", finalText.get());
  }

  @Test
  public void structuredGatewayStillFeedsLegacyAgentObserverExactlyOnce() {
    AtomicInteger callbacks = new AtomicInteger();
    AtomicReference<String> streamed = new AtomicReference<>();
    ModelGateway gateway =
        (request, observer) -> {
          observer.onStreamDelta(ModelStreamDelta.text("chunk", "reason"));
          observer.onComplete(new ModelResponse("done", "stop", Collections.emptyList()));
          return Cancellable.NONE;
        };
    AgentEngine engine =
        new AgentEngine(
            definition(Collections.emptyList(), Collections.emptyList()),
            gateway,
            new ModelEndpoint("http://localhost", "", "m", 0, false, false),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);
    AtomicReference<String> finalText = new AtomicReference<>();
    AgentObserver observer =
        new AgentObserver() {
          public void onState(String state) {}

          public void onDelta(String content, String reasoning) {
            callbacks.incrementAndGet();
            streamed.set(content + ":" + reasoning);
          }

          public void onToolStarted(String id, String name, ToolArguments arguments) {}

          public void onToolProgress(
              String id, String stage, long current, long total, String message) {}

          public void onToolCompleted(String id, String name, ToolResult result) {}

          public void onValidation(ValidationResult result) {}

          public void onFinal(String content) {
            finalText.set(content);
          }

          public void onError(Throwable error) {
            throw new AssertionError(error);
          }
        };

    engine.submit("task", observer);

    assertEquals(1, callbacks.get());
    assertEquals("chunk:reason", streamed.get());
    assertEquals("done", finalText.get());
  }

  private static AgentTool tool(String name, boolean terminal) {
    return new AgentTool() {
      public ToolSpec spec() {
        return new ToolSpec(name, "", Collections.singletonMap("type", "object"));
      }

      public boolean requestsFinalize() {
        return terminal;
      }

      public Cancellable execute(ToolContext c, ToolArguments a, ToolCallback cb) {
        cb.onComplete(ToolResult.success());
        return Cancellable.NONE;
      }
    };
  }

  private static WorkbenchDefinition definition(
      List<AgentTool> tools, List<PromptContributor> prompts) {
    return new WorkbenchDefinition() {
      public String id() {
        return "d";
      }

      public String displayName() {
        return "d";
      }

      public List<PromptContributor> promptContributors() {
        return prompts;
      }

      public List<ContextProvider> contextProviders() {
        return Collections.emptyList();
      }

      public List<AgentTool> tools() {
        return tools;
      }

      public List<ToolPolicy> toolPolicies() {
        return Collections.emptyList();
      }

      public List<TaskValidator> validators() {
        return Collections.emptyList();
      }

      public WorkbenchHost host() {
        return new WorkbenchHost() {
          public void openArtifact(String x) {}

          public void refreshArtifacts() {}

          public void handleAction(String a, ToolArguments b) {}

          public void onEvent(WorkbenchEvent e) {}
        };
      }
    };
  }

  private static UserDecisionService noopDecisions() {
    return (r, c) -> Cancellable.NONE;
  }

  private static AgentObserver observer(AtomicReference<String> out) {
    return new AgentObserver() {
      public void onState(String s) {}

      public void onDelta(String c, String r) {}

      public void onToolStarted(String i, String n, ToolArguments a) {}

      public void onToolProgress(String i, String s, long c, long t, String m) {}

      public void onToolCompleted(String i, String n, ToolResult r) {}

      public void onValidation(ValidationResult r) {}

      public void onFinal(String c) {
        out.set(c);
      }

      public void onError(Throwable e) {
        throw new AssertionError(e);
      }
    };
  }
}
