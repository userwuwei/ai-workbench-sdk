package com.cscjapp.aiworkbench.core;

import static org.junit.Assert.*;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class AgentEnginePauseResumeTest {
  @Test
  public void pausesBeforeTwentyFirstRequestAndResumesSameRunWithoutUserMessage() {
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger toolExecutions = new AtomicInteger();
    LifecyclePolicy lifecycle = new LifecyclePolicy();
    ModelGateway gateway =
        (request, observer) -> {
          int current = requests.incrementAndGet();
          if (current == 22) {
            observer.onComplete(finalResponse("done"));
          } else {
            List<AgentToolCall> calls = new ArrayList<>();
            calls.add(new AgentToolCall("step-" + current + "-a", "step", ToolArguments.empty()));
            if (current == 20) {
              calls.add(
                  new AgentToolCall("step-" + current + "-b", "step", ToolArguments.empty()));
            }
            observer.onComplete(new ModelResponse("", "tool_calls", calls));
          }
          return Cancellable.NONE;
        };
    RecordingObserver observer = new RecordingObserver();
    AgentEngine engine =
        engine(gateway, toolExecutions, lifecycle, Integer.MAX_VALUE, 20);

    engine.submit("task", observer);

    assertEquals(20, requests.get());
    assertEquals(21, toolExecutions.get());
    assertTrue(engine.isPaused());
    assertEquals(
        1,
        Collections.frequency(
            observer.states, AgentEngine.STATE_INTERACTION_LIMIT_PAUSED));
    assertEquals(1, lifecycle.startedRunIds.size());
    assertTrue(lifecycle.finishedStates.isEmpty());
    assertEquals(1, userMessageCount(engine.messages()));
    assertEquals(21, toolMessageCount(engine.messages(), "step"));

    assertTrue(engine.resumePausedRun());

    assertEquals(22, requests.get());
    assertEquals(22, toolExecutions.get());
    assertFalse(engine.isPaused());
    assertEquals("done", observer.finalText.get());
    assertEquals(1, userMessageCount(engine.messages()));
    assertEquals(22, toolMessageCount(engine.messages(), "step"));
    assertEquals(1, lifecycle.finishedStates.size());
    assertEquals("task_completed", lifecycle.finishedStates.get(0));
    assertEquals(lifecycle.startedRunIds.get(0), lifecycle.finishedRunIds.get(0));
    assertFalse(engine.resumePausedRun());
    assertTrue(observer.errors.isEmpty());
  }

  @Test
  public void terminalResponseOnTwentiethRequestCompletesWithoutPause() {
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    ModelGateway gateway =
        (request, observer) -> {
          int current = requests.incrementAndGet();
          observer.onComplete(
              current == 20
                  ? finalResponse("round-20")
                  : stepResponse("step-" + current));
          return Cancellable.NONE;
        };
    RecordingObserver observer = new RecordingObserver();
    AgentEngine engine =
        engine(gateway, executions, new LifecyclePolicy(), Integer.MAX_VALUE, 20);

    engine.submit("task", observer);

    assertEquals(20, requests.get());
    assertEquals(19, executions.get());
    assertEquals("round-20", observer.finalText.get());
    assertFalse(engine.isPaused());
    assertFalse(observer.states.contains(AgentEngine.STATE_INTERACTION_LIMIT_PAUSED));
  }

  @Test
  public void pausesAgainBeforeFortyFirstRequestAndCancelFinishesOnlyOnce() {
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    LifecyclePolicy lifecycle = new LifecyclePolicy();
    ModelGateway gateway =
        (request, observer) -> {
          int current = requests.incrementAndGet();
          observer.onComplete(stepResponse("step-" + current));
          return Cancellable.NONE;
        };
    RecordingObserver observer = new RecordingObserver();
    AgentEngine engine =
        engine(gateway, executions, lifecycle, Integer.MAX_VALUE, 20);

    engine.submit("task", observer);
    assertEquals(20, requests.get());
    assertTrue(engine.isPaused());

    assertTrue(engine.resumePausedRun());
    assertEquals(40, requests.get());
    assertEquals(40, executions.get());
    assertTrue(engine.isPaused());
    assertEquals(
        2,
        Collections.frequency(
            observer.states, AgentEngine.STATE_INTERACTION_LIMIT_PAUSED));

    engine.cancel();
    engine.cancel();

    assertFalse(engine.isPaused());
    assertEquals(Collections.singletonList("cancelled"), lifecycle.finishedStates);
  }

  @Test
  public void legacyConstructorRetainsHardMaxRoundsFailure() {
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    LifecyclePolicy lifecycle = new LifecyclePolicy();
    ModelGateway gateway =
        (request, observer) -> {
          int current = requests.incrementAndGet();
          observer.onComplete(stepResponse("step-" + current));
          return Cancellable.NONE;
        };
    RecordingObserver observer = new RecordingObserver();
    WorkbenchDefinition definition = definition(executions, lifecycle);
    AgentEngine engine =
        new AgentEngine(
            definition,
            gateway,
            endpoint(),
            noDecisions(),
            Runnable::run,
            "session",
            "workspace",
            false,
            2);

    engine.submit("task", observer);

    assertEquals(2, requests.get());
    assertFalse(engine.isPaused());
    assertEquals(1, observer.errors.size());
    assertTrue(observer.errors.get(0).getMessage().contains("2"));
    assertEquals(Collections.singletonList("max_rounds"), lifecycle.finishedStates);
  }

  @Test
  public void newSubmissionSupersedesPausedRunBeforeStartingAnotherRun() {
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    LifecyclePolicy lifecycle = new LifecyclePolicy();
    ModelGateway gateway =
        (request, observer) -> {
          int current = requests.incrementAndGet();
          observer.onComplete(stepResponse("step-" + current));
          return Cancellable.NONE;
        };
    AgentEngine engine =
        engine(gateway, executions, lifecycle, Integer.MAX_VALUE, 20);

    engine.submit("first", new RecordingObserver());
    assertTrue(engine.isPaused());
    long firstRunId = lifecycle.startedRunIds.get(0);

    engine.submit("second", new RecordingObserver());

    assertEquals(40, requests.get());
    assertTrue(engine.isPaused());
    assertEquals(2, lifecycle.startedRunIds.size());
    assertNotEquals(firstRunId, (long) lifecycle.startedRunIds.get(1));
    assertEquals(Collections.singletonList(firstRunId), lifecycle.finishedRunIds);
    assertEquals(Collections.singletonList("superseded"), lifecycle.finishedStates);
  }

  private static AgentEngine engine(
      ModelGateway gateway,
      AtomicInteger executions,
      LifecyclePolicy lifecycle,
      int maxRounds,
      int pauseEvery) {
    return new AgentEngine(
        definition(executions, lifecycle),
        gateway,
        endpoint(),
        noDecisions(),
        Runnable::run,
        "session",
        "workspace",
        false,
        maxRounds,
        pauseEvery);
  }

  private static WorkbenchDefinition definition(
      AtomicInteger executions, LifecyclePolicy lifecycle) {
    AgentTool step =
        new AgentTool() {
          public ToolSpec spec() {
            return new ToolSpec("step", "step", Collections.singletonMap("type", "object"));
          }

          public Cancellable execute(
              ToolContext context, ToolArguments arguments, ToolCallback callback) {
            executions.incrementAndGet();
            callback.onComplete(ToolResult.success());
            return Cancellable.NONE;
          }
        };
    AgentTool terminal =
        new AgentTool() {
          public ToolSpec spec() {
            return new ToolSpec(
                "finalize_task", "finalize", Collections.singletonMap("type", "object"));
          }

          public boolean requestsFinalize() {
            return true;
          }

          public Cancellable execute(
              ToolContext context, ToolArguments arguments, ToolCallback callback) {
            callback.onComplete(ToolResult.success());
            return Cancellable.NONE;
          }
        };
    return new WorkbenchDefinition() {
      public String id() {
        return "pause-test";
      }

      public String displayName() {
        return "pause-test";
      }

      public List<PromptContributor> promptContributors() {
        return Collections.emptyList();
      }

      public List<ContextProvider> contextProviders() {
        return Collections.emptyList();
      }

      public List<AgentTool> tools() {
        return Arrays.asList(step, terminal);
      }

      public List<ToolPolicy> toolPolicies() {
        return Collections.singletonList(lifecycle);
      }

      public List<TaskValidator> validators() {
        return Collections.emptyList();
      }

      public WorkbenchHost host() {
        return new WorkbenchHost() {
          public void openArtifact(String artifactId) {}

          public void refreshArtifacts() {}

          public void handleAction(String actionId, ToolArguments arguments) {}

          public void onEvent(WorkbenchEvent event) {}
        };
      }
    };
  }

  private static ModelResponse stepResponse(String id) {
    return new ModelResponse(
        "",
        "tool_calls",
        Collections.singletonList(new AgentToolCall(id, "step", ToolArguments.empty())));
  }

  private static ModelResponse finalResponse(String summary) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("status", "completed");
    arguments.put("summary", summary);
    return new ModelResponse(
        "",
        "tool_calls",
        Collections.singletonList(
            new AgentToolCall(
                "final-" + summary, "finalize_task", new ToolArguments(arguments))));
  }

  private static int userMessageCount(List<AgentMessage> messages) {
    int count = 0;
    for (AgentMessage message : messages) {
      if (message.role() == AgentMessage.Role.USER) count++;
    }
    return count;
  }

  private static int toolMessageCount(List<AgentMessage> messages, String name) {
    int count = 0;
    for (AgentMessage message : messages) {
      if (message.role() == AgentMessage.Role.TOOL && name.equals(message.name())) count++;
    }
    return count;
  }

  private static ModelEndpoint endpoint() {
    return new ModelEndpoint("http://localhost", "", "model", 0, true, false);
  }

  private static UserDecisionService noDecisions() {
    return (request, callback) -> Cancellable.NONE;
  }

  private static final class LifecyclePolicy implements ToolPolicy, AgentRunLifecycle {
    final List<Long> startedRunIds = new ArrayList<>();
    final List<Long> finishedRunIds = new ArrayList<>();
    final List<String> finishedStates = new ArrayList<>();

    public boolean supports(ToolInvocation invocation) {
      return false;
    }

    public Cancellable evaluate(
        ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback) {
      throw new AssertionError("lifecycle-only policy must not evaluate tools");
    }

    public void onRunStarted(AgentRunContext context) {
      startedRunIds.add(context.runId());
    }

    public void onRunFinished(AgentRunContext context, String state) {
      finishedRunIds.add(context.runId());
      finishedStates.add(state);
    }
  }

  private static final class RecordingObserver implements AgentObserver {
    final List<String> states = new ArrayList<>();
    final List<Throwable> errors = new ArrayList<>();
    final AtomicReference<String> finalText = new AtomicReference<>();

    public void onState(String state) {
      states.add(state);
    }

    public void onDelta(String content, String reasoning) {}

    public void onToolStarted(String id, String name, ToolArguments arguments) {}

    public void onToolProgress(
        String id, String stage, long current, long total, String message) {}

    public void onToolCompleted(String id, String name, ToolResult result) {}

    public void onValidation(ValidationResult result) {}

    public void onFinal(String content) {
      finalText.set(content);
    }

    public void onError(Throwable error) {
      errors.add(error);
    }
  }
}
