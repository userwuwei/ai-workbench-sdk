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
  public void multipleToolCallsAreEnabledByRoundPolicyAndExecutedInOrder() {
    class BatchPolicy implements ToolPolicy, MultipleToolCallPolicy {
      public boolean allowMultipleToolCalls(AgentRoundContext context) {
        return context.round() == 0;
      }

      public boolean supports(ToolInvocation invocation) {
        return false;
      }

      public Cancellable evaluate(
          ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
        throw new AssertionError("round-only policy must not evaluate invocations");
      }
    }
    BatchPolicy policy = new BatchPolicy();
    AtomicInteger firstExecutions = new AtomicInteger();
    AtomicInteger secondExecutions = new AtomicInteger();
    List<Boolean> multipleFlags = new ArrayList<>();
    AtomicInteger rounds = new AtomicInteger();
    ModelGateway gateway = (request, observer) -> {
      multipleFlags.add(request.allowMultipleToolCalls());
      int round = rounds.incrementAndGet();
      if (round == 1) {
        observer.onComplete(new ModelResponse(
            "",
            "tool_calls",
            Arrays.asList(
                new AgentToolCall("first", "write_first", ToolArguments.empty()),
                new AgentToolCall("second", "write_second", ToolArguments.empty()))));
      } else {
        observer.onComplete(new ModelResponse(
            "",
            "tool_calls",
            Collections.singletonList(new AgentToolCall(
                "final",
                "finalize_task",
                new ToolArguments(map("status", "completed", "summary", "done"))))));
      }
      return Cancellable.NONE;
    };
    WorkbenchDefinition base = definition(
        Arrays.asList(
            countingTool("write_first", firstExecutions),
            countingTool("write_second", secondExecutions),
            tool("finalize_task", true)),
        Collections.emptyList());
    WorkbenchDefinition routed = new WorkbenchDefinition() {
      public String id() { return base.id(); }
      public String displayName() { return base.displayName(); }
      public List<PromptContributor> promptContributors() { return base.promptContributors(); }
      public List<ContextProvider> contextProviders() { return base.contextProviders(); }
      public List<AgentTool> tools() { return base.tools(); }
      public List<ToolPolicy> toolPolicies() { return Collections.singletonList(policy); }
      public List<TaskValidator> validators() { return base.validators(); }
      public WorkbenchHost host() { return base.host(); }
    };
    AgentEngine engine = new AgentEngine(
        routed,
        gateway,
        new ModelEndpoint("http://localhost", "", "m", 0, true, false),
        noopDecisions(),
        Runnable::run,
        "s",
        "w",
        false,
        4);

    engine.submit("task", observer(new AtomicReference<>()));

    assertEquals(Arrays.asList(true, false), multipleFlags);
    assertEquals(1, firstExecutions.get());
    assertEquals(1, secondExecutions.get());
    List<String> writes = new ArrayList<>();
    for (AgentMessage message : engine.messages()) {
      if (message.role() == AgentMessage.Role.TOOL
          && ("write_first".equals(message.name()) || "write_second".equals(message.name()))) {
        writes.add(message.name());
      }
    }
    assertEquals(Arrays.asList("write_first", "write_second"), writes);
  }

  @Test
  public void roundToolSelectionIsAdvisoryForRegisteredTools() {
    AtomicInteger rounds = new AtomicInteger();
    AtomicInteger hiddenExecutions = new AtomicInteger();
    ToolPolicy selectionPolicy = new ToolPolicy() {
      @Override
      public ToolSelection selectTools(AgentRoundContext context, List<ToolSpec> tools) {
        return ToolSelection.onlyNames(tools, Arrays.asList("read_plan", "finalize_task"));
      }

      public boolean supports(ToolInvocation invocation) { return false; }

      public Cancellable evaluate(
          ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
        throw new AssertionError("selection-only policy must not evaluate invocations");
      }
    };
    AgentTool hidden = new AgentTool() {
      public ToolSpec spec() {
        return new ToolSpec("read_file", "read_file", Collections.singletonMap("type", "object"));
      }

      public Cancellable execute(
          ToolContext context, ToolArguments arguments, ToolCallback callback) {
        hiddenExecutions.incrementAndGet();
        callback.onComplete(ToolResult.success());
        return Cancellable.NONE;
      }
    };
    ModelGateway gateway = (request, observer) -> {
      int round = rounds.incrementAndGet();
      String name = round == 1 ? "read_file" : "finalize_task";
      ToolArguments arguments = round == 1
          ? ToolArguments.empty()
          : new ToolArguments(map("status", "completed", "summary", "done"));
      observer.onComplete(new ModelResponse(
          "", "tool_calls", Collections.singletonList(new AgentToolCall("call-" + round, name, arguments))));
      return Cancellable.NONE;
    };
    WorkbenchDefinition base = definition(
        Arrays.asList(hidden, tool("read_plan", false), tool("finalize_task", true)),
        Collections.emptyList());
    WorkbenchDefinition routed = new WorkbenchDefinition() {
      public String id() { return base.id(); }
      public String displayName() { return base.displayName(); }
      public List<PromptContributor> promptContributors() { return base.promptContributors(); }
      public List<ContextProvider> contextProviders() { return base.contextProviders(); }
      public List<AgentTool> tools() { return base.tools(); }
      public List<ToolPolicy> toolPolicies() { return Collections.singletonList(selectionPolicy); }
      public List<TaskValidator> validators() { return base.validators(); }
      public WorkbenchHost host() { return base.host(); }
    };
    AtomicReference<String> finalText = new AtomicReference<>();
    AgentEngine engine = new AgentEngine(
        routed,
        gateway,
        new ModelEndpoint("http://localhost", "", "m", 0, true, false),
        noopDecisions(),
        Runnable::run,
        "s",
        "w",
        false,
        4);

    engine.submit("task", observer(finalText));

    assertEquals(2, rounds.get());
    assertEquals(1, hiddenExecutions.get());
    AgentMessage executed = engine.messages().stream().filter(message ->
        message.role() == AgentMessage.Role.TOOL
            && "read_file".equals(message.name()))
        .findFirst().orElseThrow(AssertionError::new);
    assertTrue(executed.content().contains("\"status\":\"success\""));
    assertFalse(executed.content().contains("tool_not_selected"));
    assertEquals("done", finalText.get());
  }

  @Test
  public void recommendedActionDoesNotGateARegisteredTool() {
    AtomicInteger rounds = new AtomicInteger();
    AtomicInteger hiddenExecutions = new AtomicInteger();
    AgentTool hidden = countingTool("read_file", hiddenExecutions);
    AgentTool routed = new AgentTool() {
      public ToolSpec spec() {
        return new ToolSpec("read_plan", "read_plan", Collections.singletonMap("type", "object"));
      }

      public Cancellable execute(
          ToolContext context, ToolArguments arguments, ToolCallback callback) {
        callback.onComplete(ToolResult.success(
            map("recommended_next_action", "finalize_task")));
        return Cancellable.NONE;
      }
    };
    ToolPolicy policy = new ToolPolicy() {
      public ToolSelection selectTools(AgentRoundContext context, List<ToolSpec> tools) {
        return ToolSelection.onlyNames(tools, Arrays.asList("read_plan", "finalize_task"));
      }
      public boolean supports(ToolInvocation invocation) { return false; }
      public Cancellable evaluate(
          ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
        throw new AssertionError("selection-only policy must not evaluate invocations");
      }
    };
    ModelGateway gateway = (request, observer) -> {
      int round = rounds.incrementAndGet();
      String name = round == 1 ? "read_plan" : round == 2 ? "read_file" : "finalize_task";
      ToolArguments arguments = "finalize_task".equals(name)
          ? new ToolArguments(map("status", "completed", "summary", "done"))
          : ToolArguments.empty();
      observer.onComplete(new ModelResponse(
          "", "tool_calls", Collections.singletonList(
              new AgentToolCall("call-" + round, name, arguments))));
      return Cancellable.NONE;
    };
    WorkbenchDefinition base = definition(
        Arrays.asList(hidden, routed, tool("finalize_task", true)),
        Collections.emptyList());
    WorkbenchDefinition definition = new WorkbenchDefinition() {
      public String id() { return base.id(); }
      public String displayName() { return base.displayName(); }
      public List<PromptContributor> promptContributors() { return base.promptContributors(); }
      public List<ContextProvider> contextProviders() { return base.contextProviders(); }
      public List<AgentTool> tools() { return base.tools(); }
      public List<ToolPolicy> toolPolicies() { return Collections.singletonList(policy); }
      public List<TaskValidator> validators() { return base.validators(); }
      public WorkbenchHost host() { return base.host(); }
    };
    AgentEngine engine = new AgentEngine(
        definition, gateway, new ModelEndpoint("http://localhost", "", "m", 0, true, false),
        noopDecisions(), Runnable::run, "s", "w", false, 5);

    engine.submit("task", observer(new AtomicReference<>()));

    assertEquals(3, rounds.get());
    assertEquals(1, hiddenExecutions.get());
    AgentMessage executed = engine.messages().stream().filter(message ->
        message.role() == AgentMessage.Role.TOOL
            && "read_file".equals(message.name()))
        .findFirst().orElseThrow(AssertionError::new);
    assertTrue(executed.content().contains("\"status\":\"success\""));
    assertFalse(executed.content().contains("tool_not_selected"));
  }

  @Test
  public void unselectedCallIsPairedAndLaterSelectedCallStillExecutes() {
    AtomicInteger rounds = new AtomicInteger();
    AtomicInteger hiddenExecutions = new AtomicInteger();
    AtomicInteger selectedExecutions = new AtomicInteger();
    AgentTool hidden = countingTool("read_file", hiddenExecutions);
    AgentTool selected = countingTool("read_plan", selectedExecutions);
    ToolPolicy policy = new ToolPolicy() {
      public ToolSelection selectTools(AgentRoundContext context, List<ToolSpec> tools) {
        return ToolSelection.onlyNames(tools, Arrays.asList("read_plan", "finalize_task"));
      }
      public boolean supports(ToolInvocation invocation) { return false; }
      public Cancellable evaluate(
          ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
        throw new AssertionError("selection-only policy must not evaluate invocations");
      }
    };
    ModelGateway gateway = (request, observer) -> {
      int round = rounds.incrementAndGet();
      if (round == 1) {
        observer.onComplete(new ModelResponse(
            "",
            "tool_calls",
            Arrays.asList(
                new AgentToolCall("hidden", "read_file", ToolArguments.empty()),
                new AgentToolCall("selected", "read_plan", ToolArguments.empty()))));
      } else {
        observer.onComplete(new ModelResponse(
            "",
            "tool_calls",
            Collections.singletonList(new AgentToolCall(
                "final", "finalize_task",
                new ToolArguments(map("status", "completed", "summary", "done"))))));
      }
      return Cancellable.NONE;
    };
    WorkbenchDefinition base = definition(
        Arrays.asList(hidden, selected, tool("finalize_task", true)),
        Collections.emptyList());
    WorkbenchDefinition routed = new WorkbenchDefinition() {
      public String id() { return base.id(); }
      public String displayName() { return base.displayName(); }
      public List<PromptContributor> promptContributors() { return base.promptContributors(); }
      public List<ContextProvider> contextProviders() { return base.contextProviders(); }
      public List<AgentTool> tools() { return base.tools(); }
      public List<ToolPolicy> toolPolicies() { return Collections.singletonList(policy); }
      public List<TaskValidator> validators() { return base.validators(); }
      public WorkbenchHost host() { return base.host(); }
    };
    AgentEngine engine = new AgentEngine(
        routed, gateway, new ModelEndpoint("http://localhost", "", "m", 0, true, false),
        noopDecisions(), Runnable::run, "s", "w", false, 4);

    engine.submit("task", observer(new AtomicReference<>()));

    assertEquals(1, hiddenExecutions.get());
    assertEquals(1, selectedExecutions.get());
    assertEquals(2, engine.messages().stream()
        .filter(message -> message.role() == AgentMessage.Role.TOOL
            && ("read_file".equals(message.name()) || "read_plan".equals(message.name())))
        .count());
  }

  @Test
  public void unregisteredToolStillReturnsUnsupportedToolAndKeepsProtocolPaired() {
    AtomicInteger rounds = new AtomicInteger();
    ModelGateway gateway = (request, observer) -> {
      int round = rounds.incrementAndGet();
      if (round == 1) {
        observer.onComplete(new ModelResponse(
            "",
            "tool_calls",
            Collections.singletonList(
                new AgentToolCall("unknown", "missing_tool", ToolArguments.empty()))));
      } else {
        observer.onComplete(new ModelResponse(
            "",
            "tool_calls",
            Collections.singletonList(new AgentToolCall(
                "final",
                "finalize_task",
                new ToolArguments(map("status", "completed", "summary", "done"))))));
      }
      return Cancellable.NONE;
    };
    WorkbenchDefinition definition = definition(
        Collections.singletonList(tool("finalize_task", true)), Collections.emptyList());
    AtomicReference<String> finalText = new AtomicReference<>();
    AgentEngine engine = new AgentEngine(
        definition,
        gateway,
        new ModelEndpoint("http://localhost", "", "m", 0, true, false),
        noopDecisions(),
        Runnable::run,
        "s",
        "w",
        false,
        4);

    engine.submit("task", observer(finalText));

    assertEquals(2, rounds.get());
    AgentMessage unsupported = engine.messages().stream().filter(message ->
        message.role() == AgentMessage.Role.TOOL && "missing_tool".equals(message.name()))
        .findFirst().orElseThrow(AssertionError::new);
    assertTrue(unsupported.content().contains("unsupported_tool"));
    assertEquals("done", finalText.get());
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

  @Test
  public void bestEffortInvalidArgumentsRetryOnceWithoutDispatching() {
    AtomicInteger rounds = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    AtomicInteger policyChecks = new AtomicInteger();
    AtomicInteger invalidNotices = new AtomicInteger();
    AtomicReference<String> finalText = new AtomicReference<>();
    ModelGateway gateway =
        (request, observer) -> {
          int round = rounds.incrementAndGet();
          if (round == 1) {
            observer.onComplete(invalidResponse("call-invalid", "\\U", 42));
          } else {
            observer.onComplete(
                new ModelResponse(
                    "",
                    "tool_calls",
                    Collections.singletonList(
                        new AgentToolCall(
                            "call-final",
                            "finalize_task",
                            new ToolArguments(map("status", "completed", "summary", "done"))))));
          }
          return Cancellable.NONE;
        };
    WorkbenchDefinition base =
        definition(
            Arrays.asList(countingTool("create_file", executions), tool("finalize_task", true)),
            Collections.emptyList());
    ToolPolicy policy =
        new ToolPolicy() {
          public boolean supports(ToolInvocation invocation) {
            policyChecks.incrementAndGet();
            return false;
          }

          public Cancellable evaluate(
              ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
            throw new AssertionError("invalid arguments must not reach policies");
          }
        };
    WorkbenchDefinition routed = withPolicies(base, Collections.singletonList(policy));
    AgentEngine engine =
        new AgentEngine(
            routed,
            gateway,
            new ModelEndpoint(
                "http://localhost",
                "",
                "m",
                0,
                true,
                false,
                ToolArgumentMode.BEST_EFFORT),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            4);

    engine.submit(
        "task",
        recordingObserver(
            finalText,
            new AtomicReference<>(),
            invalidNotices));

    assertEquals(2, rounds.get());
    assertEquals(0, executions.get());
    assertEquals(1, policyChecks.get()); // Only finalize_task enters the dispatcher.
    assertEquals(1, invalidNotices.get());
    assertEquals("done", finalText.get());
    assertTrue(
        engine.messages().stream()
            .anyMatch(
                message ->
                    message.role() == AgentMessage.Role.USER
                        && message.content().contains("禁止在 JSON 层直接使用")));
    assertFalse(
        engine.messages().stream()
            .anyMatch(
                message ->
                    message.role() == AgentMessage.Role.TOOL
                        && "call-invalid".equals(message.toolCallId())));
  }

  @Test
  public void bestEffortStopsAfterSecondInvalidArgumentsAndStrictStopsImmediately() {
    AtomicInteger bestEffortRounds = new AtomicInteger();
    ModelGateway invalidGateway =
        (request, observer) -> {
          bestEffortRounds.incrementAndGet();
          observer.onComplete(invalidResponse("call-invalid", "\\u{", 17));
          return Cancellable.NONE;
        };
    AtomicReference<Throwable> bestEffortError = new AtomicReference<>();
    AtomicInteger bestEffortNotices = new AtomicInteger();
    AgentEngine bestEffort =
        new AgentEngine(
            definition(Collections.singletonList(countingTool("create_file", new AtomicInteger())),
                Collections.emptyList()),
            invalidGateway,
            new ModelEndpoint(
                "http://localhost",
                "",
                "m",
                0,
                true,
                false,
                ToolArgumentMode.BEST_EFFORT),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);

    bestEffort.submit(
        "task",
        recordingObserver(
            new AtomicReference<>(), bestEffortError, bestEffortNotices));

    assertEquals(2, bestEffortRounds.get());
    assertEquals(2, bestEffortNotices.get());
    assertNotNull(bestEffortError.get());

    AtomicInteger strictRounds = new AtomicInteger();
    AtomicReference<Throwable> strictError = new AtomicReference<>();
    AgentEngine strict =
        new AgentEngine(
            definition(Collections.singletonList(countingTool("create_file", new AtomicInteger())),
                Collections.emptyList()),
            (request, observer) -> {
              strictRounds.incrementAndGet();
              observer.onComplete(invalidResponse("call-strict", "\\U", 9));
              return Cancellable.NONE;
            },
            new ModelEndpoint(
                "http://localhost", "", "m", 0, true, false, ToolArgumentMode.STRICT),
            noopDecisions(),
            Runnable::run,
            "s",
            "w",
            false,
            5);

    strict.submit(
        "task",
        recordingObserver(new AtomicReference<>(), strictError, new AtomicInteger()));

    assertEquals(1, strictRounds.get());
    assertNotNull(strictError.get());
    assertTrue(strictError.get().getMessage().contains("工具参数 JSON 非法"));
  }

  private static ModelResponse invalidResponse(
      String callId, String invalidEscape, int offset) {
    return new ModelResponse(
        "",
        "tool_calls",
        Collections.singletonList(
            new AgentToolCall(
                "call-valid-alongside-invalid",
                "create_file",
                new ToolArguments(map("path", "must-not-be-written.txt", "content", "blocked")))),
        Collections.singletonList(
            new InvalidToolCall(
                callId,
                "create_file",
                "Invalid escape sequence",
                invalidEscape,
                offset,
                100)));
  }

  private static WorkbenchDefinition withPolicies(
      WorkbenchDefinition base, List<ToolPolicy> policies) {
    return new WorkbenchDefinition() {
      public String id() { return base.id(); }
      public String displayName() { return base.displayName(); }
      public List<PromptContributor> promptContributors() { return base.promptContributors(); }
      public List<ContextProvider> contextProviders() { return base.contextProviders(); }
      public List<AgentTool> tools() { return base.tools(); }
      public List<ToolPolicy> toolPolicies() { return policies; }
      public List<TaskValidator> validators() { return base.validators(); }
      public WorkbenchHost host() { return base.host(); }
    };
  }

  private static AgentObserver recordingObserver(
      AtomicReference<String> finalText,
      AtomicReference<Throwable> error,
      AtomicInteger invalidNotices) {
    return new AgentObserver() {
      public void onState(String state) {}
      public void onDelta(String content, String reasoning) {}
      public void onToolStarted(String id, String name, ToolArguments arguments) {}
      public void onToolProgress(
          String id, String stage, long current, long total, String message) {}
      public void onToolCompleted(String id, String name, ToolResult result) {
        if ("invalid_tool_arguments".equals(result.errorCode())) invalidNotices.incrementAndGet();
      }
      public void onValidation(ValidationResult result) {}
      public void onFinal(String content) { finalText.set(content); }
      public void onError(Throwable throwable) { error.set(throwable); }
    };
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

  private static AgentTool countingTool(String name, AtomicInteger executions) {
    return new AgentTool() {
      public ToolSpec spec() {
        return new ToolSpec(name, "", Collections.singletonMap("type", "object"));
      }

      public Cancellable execute(ToolContext c, ToolArguments a, ToolCallback cb) {
        executions.incrementAndGet();
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

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index + 1 < values.length; index += 2) {
      result.put(String.valueOf(values[index]), values[index + 1]);
    }
    return result;
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
