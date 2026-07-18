package com.cscjapp.aiworkbench.codeagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.AgentRunContext;
import com.cscjapp.aiworkbench.api.AgentTool;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ContextProvider;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.PromptContext;
import com.cscjapp.aiworkbench.api.PromptContributor;
import com.cscjapp.aiworkbench.api.TaskValidator;
import com.cscjapp.aiworkbench.api.ToolArguments;
import com.cscjapp.aiworkbench.api.ToolCallback;
import com.cscjapp.aiworkbench.api.ToolContext;
import com.cscjapp.aiworkbench.api.ToolInvocation;
import com.cscjapp.aiworkbench.api.ToolPolicy;
import com.cscjapp.aiworkbench.api.ToolPolicyDecision;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ToolSpec;
import com.cscjapp.aiworkbench.api.ValidationContext;
import com.cscjapp.aiworkbench.api.ValidationResult;
import com.cscjapp.aiworkbench.api.WorkbenchDefinition;
import com.cscjapp.aiworkbench.api.WorkbenchEvent;
import com.cscjapp.aiworkbench.api.WorkbenchHost;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.core.AgentEngine;
import com.cscjapp.aiworkbench.core.AgentMessage;
import com.cscjapp.aiworkbench.core.AgentObserver;
import com.cscjapp.aiworkbench.core.AgentToolCall;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.ModelResponse;
import com.cscjapp.aiworkbench.core.PromptComposer;
import com.cscjapp.aiworkbench.tools.file.FileToolSet;
import com.cscjapp.aiworkbench.tools.file.LocalWorkspaceAccess;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class CodeAgentPresetTest {
  private File root;
  private LocalWorkspaceAccess workspace;

  @Before
  public void setUp() throws Exception {
    root = Files.createTempDirectory("aiw-code-agent").toFile();
    Files.write(
        new File(root, "main.txt").toPath(),
        "before".getBytes(StandardCharsets.UTF_8));
    workspace = new LocalWorkspaceAccess("workspace", root);
  }

  @After
  public void tearDown() {
    delete(root);
  }

  @Test
  public void commonPromptIsLanguageNeutralAndProfileRulesRemainSeparate() {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile("language-specific-rules"))
            .workspace(workspace)
            .languageTools(FileToolSet.standard(workspace))
            .build();
    String composed =
        new PromptComposer()
            .compose(
                definition(preset),
                new PromptContext(
                    "workspace",
                    "task",
                    Collections.singletonMap("native_tools", true)));
    String common =
        preset.promptContributors().get(0).contribute(
                new PromptContext("workspace", "task", Collections.emptyMap()))
            .get(0)
            .content();
    assertFalse(common.contains("HTML"));
    assertFalse(common.contains("Python"));
    assertFalse(common.contains("WebView"));
    assertFalse(common.contains("src/index"));
    assertTrue(common.contains("finalize_task"));
    assertTrue(composed.contains("language-specific-rules"));
  }

  @Test
  public void metaSchemaExtensionMergesWithoutReplacingBaseProperties() {
    Map<String, Object> extraProperty = new LinkedHashMap<>();
    extraProperty.put("type", "string");
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("runtime_metric", extraProperty);
    Map<String, Object> extension = new LinkedHashMap<>();
    extension.put("properties", properties);
    Map<String, Map<String, Object>> extensions = new LinkedHashMap<>();
    extensions.put(CodeAgentToolNames.QUALITY_REVIEW, extension);

    CodeLanguageProfile profile =
        CodeLanguageProfile.builder("test")
            .verificationContract(CodeValidationContract.builder().build())
            .metaToolExtensions(extensions)
            .build();
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile).workspace(workspace).build();
    ToolSpec quality = find(preset.tools(), CodeAgentToolNames.QUALITY_REVIEW).spec();
    Map<?, ?> schemaProperties = (Map<?, ?>) quality.inputSchema().get("properties");
    assertTrue(schemaProperties.containsKey("passed"));
    assertTrue(schemaProperties.containsKey("review_target"));
    assertTrue(schemaProperties.containsKey("dimension_reviews"));
    assertTrue(schemaProperties.containsKey("evidence_checked"));
    assertTrue(schemaProperties.containsKey("runtime_metric"));
    Map<?, ?> planProperties =
        (Map<?, ?>)
            find(preset.tools(), CodeAgentToolNames.PLAN_TASK)
                .spec()
                .inputSchema()
                .get("properties");
    assertTrue(planProperties.containsKey("task_type"));
    assertTrue(planProperties.containsKey("implementation_shape"));
  }

  @Test
  public void editRequiresCurrentRunReadButCreateDoesNot() throws Exception {
    Map<String, CodeToolRole> roles = new LinkedHashMap<>();
    roles.put("read_file", CodeToolRole.READ);
    roles.put("search_replace", CodeToolRole.EDIT);
    roles.put("create_file", CodeToolRole.CREATE);
    ReadBeforeEditPolicy policy = new ReadBeforeEditPolicy(workspace, roles);
    AgentTool read = tool("read_file");
    AgentTool edit = tool("search_replace");
    AgentTool create = tool("create_file");
    ToolArguments path =
        new ToolArguments(Collections.singletonMap("path", "main.txt"));

    AgentRunContext first = new AgentRunContext(1L, "s", "workspace", "task");
    policy.onRunStarted(first);
    assertFalse(policy.supports(new ToolInvocation("c", create, path)));
    ToolPolicyDecision blocked =
        decision(policy, new ToolInvocation("e1", edit, path));
    assertEquals(ToolPolicyDecision.Kind.ERROR, blocked.kind());
    assertTrue(blocked.result().retryable());
    policy.onToolCompleted(
        first,
        new ToolInvocation("r", read, path),
        ToolResult.success(Collections.singletonMap("path", "main.txt")));
    assertEquals(
        ToolPolicyDecision.Kind.PROCEED,
        decision(policy, new ToolInvocation("e2", edit, path)).kind());

    policy.onRunStarted(new AgentRunContext(2L, "s", "workspace", "next"));
    assertEquals(
        ToolPolicyDecision.Kind.ERROR,
        decision(policy, new ToolInvocation("e3", edit, path)).kind());
  }

  @Test
  public void validationContractRequiresTerminalEvidenceVerificationAndQuality() {
    CodeValidationContract contract =
        CodeValidationContract.builder()
            .defaultRequiredEvidence("compile_test")
            .exemptCompletionTypes("explain")
            .requireQualityReview("ui_product")
            .build();
    CodeCompletionValidator validator = new CodeCompletionValidator(contract);

    ValidationResult missing = validate(validator, Collections.emptyList());
    assertFalse(missing.passed());
    assertTrue(hasIssue(missing, "finalize_task_missing"));

    List<ToolResult> uiEvidence = new ArrayList<>();
    uiEvidence.add(evidence("compile_test", Collections.singletonMap("passed", true)));
    uiEvidence.add(
        evidence(
            CodeAgentToolNames.QUALITY_REVIEW,
            map("passed", false, "blocking_gaps", Collections.singletonList("gap"))));
    uiEvidence.add(finalizeEvidence("completed", "ui_product"));
    ValidationResult failedQuality = validate(validator, uiEvidence);
    assertTrue(hasIssue(failedQuality, "quality_review_failed"));

    uiEvidence.set(
        1,
        evidence(
            CodeAgentToolNames.QUALITY_REVIEW,
            map(
                "passed",
                true,
                "minimal_version_risk",
                false,
                "blocking_gaps",
                Collections.emptyList())));
    assertTrue(validate(validator, uiEvidence).passed());
    List<ToolResult> latestFailure = new ArrayList<>(uiEvidence);
    latestFailure.add(
        ToolResult.error(
            "compile_failed",
            "failed",
            true,
            map("operation", "compile_test", "passed", false)));
    assertTrue(hasIssue(validate(validator, latestFailure), "compile_test_failed"));
    assertTrue(
        validate(
                validator,
                Collections.singletonList(finalizeEvidence("completed", "explain")))
            .passed());
    assertTrue(
        validate(
                validator,
                Collections.singletonList(finalizeEvidence("blocked", "")))
            .passed());
  }

  @Test
  public void nativeAndLegacyCannotBypassFinalizeTool() {
    assertEquals(2, terminalRounds(true));
    assertEquals(2, terminalRounds(false));
  }

  private int terminalRounds(boolean nativeTools) {
    CodeAgentPreset preset =
        CodeAgentPreset.builder(profile(""))
            .workspace(workspace)
            .languageTools(FileToolSet.standard(workspace))
            .build();
    AtomicInteger rounds = new AtomicInteger();
    ModelGateway gateway =
        (request, observer) -> {
          if (rounds.incrementAndGet() == 1) {
            String content =
                nativeTools
                    ? "普通文本"
                    : "{\"next_action\":{\"tool\":\"final\",\"args\":{\"summary\":\"伪终态\"}}}";
            observer.onComplete(new ModelResponse(content, "stop", Collections.emptyList()));
          } else {
            observer.onComplete(
                new ModelResponse(
                    "",
                    "tool_calls",
                    Collections.singletonList(
                        new AgentToolCall(
                            "final",
                            CodeAgentToolNames.FINALIZE_TASK,
                            new ToolArguments(
                                map(
                                    "status",
                                    "completed",
                                    "completion_type",
                                    "explain",
                                    "summary",
                                    "done"))))));
          }
          return Cancellable.NONE;
        };
    AgentEngine engine =
        new AgentEngine(
            definition(preset),
            gateway,
            new ModelEndpoint("http://localhost", "", "model", 0.2, nativeTools, false),
            (request, callback) -> Cancellable.NONE,
            Runnable::run,
            "session",
            "workspace",
            false,
            5);
    AtomicReference<String> output = new AtomicReference<>();
    engine.submit("explain", observer(output));
    assertEquals("done", output.get());
    return rounds.get();
  }

  private CodeLanguageProfile profile(String rules) {
    return CodeLanguageProfile.builder("test")
        .languageRules(rules)
        .verificationContract(
            CodeValidationContract.builder().exemptCompletionTypes("explain").build())
        .build();
  }

  private WorkbenchDefinition definition(CodeAgentPreset preset) {
    return new WorkbenchDefinition() {
      @Override
      public String id() {
        return "test";
      }

      @Override
      public String displayName() {
        return "test";
      }

      @Override
      public List<PromptContributor> promptContributors() {
        return preset.promptContributors();
      }

      @Override
      public List<ContextProvider> contextProviders() {
        return Collections.emptyList();
      }

      @Override
      public List<AgentTool> tools() {
        return preset.tools();
      }

      @Override
      public List<ToolPolicy> toolPolicies() {
        return preset.toolPolicies();
      }

      @Override
      public List<TaskValidator> validators() {
        return preset.validators();
      }

      @Override
      public WorkbenchHost host() {
        return new WorkbenchHost() {
          @Override
          public void openArtifact(String artifactId) {}

          @Override
          public void refreshArtifacts() {}

          @Override
          public void handleAction(String actionId, ToolArguments arguments) {}

          @Override
          public void onEvent(WorkbenchEvent event) {}
        };
      }
    };
  }

  private static AgentTool find(List<AgentTool> tools, String name) {
    for (AgentTool tool : tools) if (name.equals(tool.spec().name())) return tool;
    return null;
  }

  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec(name, name, Collections.singletonMap("type", "object"));
      }

      @Override
      public Cancellable execute(
          ToolContext context, ToolArguments arguments, ToolCallback callback) {
        callback.onComplete(ToolResult.success());
        return Cancellable.NONE;
      }
    };
  }

  private static ToolPolicyDecision decision(
      ReadBeforeEditPolicy policy, ToolInvocation invocation) {
    AtomicReference<ToolPolicyDecision> result = new AtomicReference<>();
    policy.evaluate(null, invocation, result::set);
    return result.get();
  }

  private static ToolResult evidence(String operation, Map<String, ?> values) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (values != null) data.putAll(values);
    data.put("operation", operation);
    return ToolResult.success(data);
  }

  private static ToolResult finalizeEvidence(String status, String completionType) {
    return evidence(
        CodeAgentToolNames.FINALIZE_TASK,
        map("status", status, "completion_type", completionType));
  }

  private static ValidationResult validate(
      CodeCompletionValidator validator, List<ToolResult> evidence) {
    AtomicReference<ValidationResult> output = new AtomicReference<>();
    validator.validate(
        new ValidationContext("session", "workspace", "task", evidence), output::set);
    return output.get();
  }

  private static boolean hasIssue(ValidationResult result, String code) {
    return result.issues().stream().anyMatch(issue -> code.equals(issue.code()));
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> output = new LinkedHashMap<>();
    for (int index = 0; index + 1 < values.length; index += 2) {
      output.put(String.valueOf(values[index]), values[index + 1]);
    }
    return output;
  }

  private static AgentObserver observer(AtomicReference<String> output) {
    return new AgentObserver() {
      @Override
      public void onState(String state) {}

      @Override
      public void onDelta(String content, String reasoning) {}

      @Override
      public void onToolStarted(String id, String name, ToolArguments arguments) {}

      @Override
      public void onToolProgress(
          String id, String stage, long current, long total, String message) {}

      @Override
      public void onToolCompleted(String id, String name, ToolResult result) {}

      @Override
      public void onValidation(ValidationResult result) {}

      @Override
      public void onFinal(String content) {
        output.set(content);
      }

      @Override
      public void onError(Throwable error) {
        throw new AssertionError(error);
      }
    };
  }

  private static void delete(File file) {
    if (file == null || !file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) delete(child);
    }
    file.delete();
  }
}
