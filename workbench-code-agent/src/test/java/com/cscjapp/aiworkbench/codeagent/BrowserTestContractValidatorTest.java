package com.cscjapp.aiworkbench.codeagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BrowserTestContractValidatorTest {
  @Test
  public void aggregatesAllPlanIssuesWithoutFailFast() {
    List<Object> actions = new ArrayList<>();
    for (int index = 0; index < 21; index++) {
      actions.add(map("type", "click", "selector", "#drop"));
    }
    Map<String, Object> invalid = map(
        "goal", "验证游戏",
        "unsupported", true,
        "scenarios", Collections.singletonList(
            map(
                "id", "game",
                "description", "验证游戏",
                "actions", actions,
                "expectations", Arrays.asList(
                    map(
                        "type", "js_boolean",
                        "expression", "dangerous(document.body)",
                        "transition", "eventually_true"),
                    map("type", "unknown", "transition", "eventually_true")))));

    BrowserTestContractValidator.Report report =
        BrowserTestContractValidator.validate(invalid, Collections.singletonList("missing"), true);

    assertFalse(report.valid());
    List<String> codes = codes(report.validationIssues());
    assertTrue(codes.contains("unsupported_field"));
    assertTrue(codes.contains("too_many_actions"));
    assertTrue(codes.contains("invalid_readonly_expression"));
    assertTrue(codes.contains("unsupported_expectation"));
    assertTrue(codes.contains("missing_scenario_id"));
    assertTrue(codes.contains("unexpected_scenario_id"));
    assertFalse(codes.contains("missing_false_to_true"));
    assertFalse(codes.contains("missing_dynamic_scenario"));
    assertEquals(20, issue(report.validationIssues(), "too_many_actions").get("maximum"));
  }

  @Test
  public void acceptsPureNumericFunctionsAndActionBoundaries() {
    List<Object> scenarios = new ArrayList<>();
    for (int scenarioIndex = 0; scenarioIndex < 3; scenarioIndex++) {
      List<Object> actions = new ArrayList<>();
      for (int actionIndex = 0; actionIndex < 20; actionIndex++) {
        actions.add(map("type", "click", "selector", "#drop"));
      }
      scenarios.add(map(
          "id", "game-" + scenarioIndex,
          "description", "验证游戏 " + scenarioIndex,
          "actions", actions,
          "expectations", Collections.singletonList(
              map(
                  "type", "js_boolean",
                  "expression", scenarioIndex == 0
                      ? "parseInt(document.body.dataset.score) > 0"
                      : "parseFloat(document.body.dataset.score) >= " + scenarioIndex,
                  "transition", "false_to_true"))));
    }

    BrowserTestContractValidator.Report report = BrowserTestContractValidator.validate(
        map("goal", "验证游戏", "scenarios", scenarios),
        Arrays.asList("game-0", "game-1", "game-2"),
        true);

    assertTrue(report.validationIssues().toString(), report.valid());
    assertEquals(60, totalActions(scenarios));
  }

  @Test
  public void rejectsTransactionActionBudgetAboveSixty() {
    List<Object> scenarios = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      List<Object> actions = new ArrayList<>();
      for (int action = 0; action < 16; action++) {
        actions.add(map("type", "click", "selector", "#drop"));
      }
      scenarios.add(map(
          "id", "game-" + index,
          "description", "验证",
          "actions", actions,
          "expectations", Collections.singletonList(
              map(
                  "type", "js_boolean",
                  "expression", "document.body.dataset.ready === 'true'",
                  "transition", "false_to_true"))));
    }

    BrowserTestContractValidator.Report report = BrowserTestContractValidator.validate(
        map("goal", "验证", "scenarios", scenarios));

    assertFalse(report.valid());
    assertTrue(codes(report.validationIssues()).contains("too_many_total_actions"));
  }

  @Test
  public void legacyFieldsAndFractionalTimeoutAreReportedTogether() {
    BrowserTestContractValidator.Report report = BrowserTestContractValidator.validate(map(
        "operation", "run_steps",
        "steps", Collections.emptyList(),
        "timeout_ms", 3000.5d,
        "goal", "验证",
        "scenarios", Collections.singletonList(map(
            "id", "static",
            "description", "静态检查",
            "actions", Collections.emptyList(),
            "expectations", Collections.singletonList(
                map("type", "selector_exists", "selector", "body"))))));

    assertFalse(report.valid());
    assertEquals(2, countCode(report.validationIssues(), "legacy_parameter"));
    assertTrue(codes(report.validationIssues()).contains("invalid_type"));
    assertTrue(report.firstMessage().contains("已移除旧参数 operation"));
  }

  @Test
  public void requiredIdsMayMixStaticAndDynamicScenarios() {
    BrowserTestContractValidator.Report report = BrowserTestContractValidator.validate(
        map("goal", "验证页面", "scenarios", Arrays.asList(
            map(
                "id", "loads",
                "description", "页面加载",
                "actions", Collections.emptyList(),
                "expectations", Collections.singletonList(
                    map("type", "selector_exists", "selector", "#game"))),
            map(
                "id", "start",
                "description", "开始游戏",
                "actions", Collections.singletonList(
                    map("type", "click", "selector", "#start")),
                "expectations", Collections.singletonList(
                    map("type", "js_boolean", "expression",
                        "document.body.dataset.running === 'true'",
                        "transition", "false_to_true"))))),
        Arrays.asList("loads", "start"),
        true);

    assertTrue(report.validationIssues().toString(), report.valid());
  }

  @Test
  public void validatesWaitForAndRejectsDuplicateNormalizedBehavior() {
    Map<String, Object> wait = map(
        "type", "wait_for",
        "expectation", map(
            "type", "js_boolean",
            "expression", "document.body.dataset.over === 'true'",
            "transition", "false_to_true"),
        "timeout_ms", 10000);
    Map<String, Object> scenario = map(
        "id", "restart",
        "actions", Arrays.asList(
            map("type", "click", "selector", "#start"),
            wait,
            map("type", "click", "selector", "#restart")),
        "expectations", Collections.singletonList(
            map("type", "js_boolean", "expression",
                "document.body.dataset.running === 'true'", "transition", "eventually_true")));
    BrowserTestContractValidator.Report valid = BrowserTestContractValidator.validate(
        map("goal", "验证重开", "scenarios", Collections.singletonList(scenario)),
        Collections.singletonList("restart"), true);
    assertTrue(valid.validationIssues().toString(), valid.valid());

    Map<String, Object> duplicate = new LinkedHashMap<>(scenario);
    duplicate.put("id", "restart-copy");
    duplicate.put("description", "另一描述");
    BrowserTestContractValidator.Report invalid = BrowserTestContractValidator.validate(
        map("goal", "验证", "scenarios", Arrays.asList(scenario, duplicate)));
    assertFalse(invalid.valid());
    assertTrue(codes(invalid.validationIssues()).contains("duplicate_scenario_behavior"));
  }

  @Test
  public void rejectsComplementaryFalseToTrueConditionsSharingCleanBaseline() {
    BrowserTestContractValidator.Report invalid = BrowserTestContractValidator.validate(map(
        "goal", "验证重开",
        "scenarios", Collections.singletonList(map(
            "id", "restart",
            "actions", Arrays.asList(
                map("type", "click", "selector", "#start"),
                map(
                    "type", "wait_for",
                    "expectation", map(
                        "type", "js_boolean",
                        "expression", " ( document.body.dataset.over === 'true' ) ",
                        "transition", "false_to_true")),
                map("type", "click", "selector", "#restart"),
                map(
                    "type", "wait_for",
                    "expectation", map(
                        "type", "js_boolean",
                        "expression", "!(document.body.dataset.over === 'true')",
                        "transition", "false_to_true"))),
            "expectations", Collections.singletonList(map(
                "type", "selector_exists", "selector", "#game"))))));

    assertFalse(invalid.valid());
    assertEquals(1, countCode(
        invalid.validationIssues(), "contradictory_false_to_true_baseline"));
  }

  @Test
  public void acceptsComplementaryResetAsEventuallyTrueAndIndependentDynamicConditions() {
    BrowserTestContractValidator.Report report = BrowserTestContractValidator.validate(map(
        "goal", "验证多阶段流程",
        "scenarios", Collections.singletonList(map(
            "id", "restart",
            "actions", Arrays.asList(
                map("type", "click", "selector", "#start"),
                map("type", "wait_for", "expectation", map(
                    "type", "js_boolean",
                    "expression", "document.body.dataset.over === 'true'",
                    "transition", "false_to_true")),
                map("type", "wait_for", "expectation", map(
                    "type", "js_boolean",
                    "expression", "document.body.dataset.ready === 'true'",
                    "transition", "false_to_true")),
                map("type", "click", "selector", "#restart"),
                map("type", "wait_for", "expectation", map(
                    "type", "js_boolean",
                    "expression", "!(document.body.dataset.over === 'true')",
                    "transition", "eventually_true"))),
            "expectations", Collections.singletonList(map(
                "type", "selector_exists", "selector", "#game"))))));

    assertTrue(report.validationIssues().toString(), report.valid());
  }

  @Test
  public void waitForOnlyScenarioAndOutOfRangeTimeoutAreReported() {
    BrowserTestContractValidator.Report report = BrowserTestContractValidator.validate(map(
        "goal", "验证",
        "scenarios", Collections.singletonList(map(
            "id", "wait-only",
            "description", "等待",
            "actions", Collections.singletonList(map(
                "type", "wait_for",
                "expectation", map("type", "selector_exists", "selector", "#ready"),
                "timeout_ms", 100)),
            "expectations", Collections.singletonList(
                map("type", "selector_exists", "selector", "#ready"))))));

    assertFalse(report.valid());
    assertTrue(codes(report.validationIssues()).contains("out_of_range"));
    assertFalse(codes(report.validationIssues()).contains("wait_for_without_interaction"));

    BrowserTestContractValidator.Report structurallyValid =
        BrowserTestContractValidator.validate(map(
            "goal", "验证",
            "scenarios", Collections.singletonList(map(
                "id", "wait-only",
                "actions", Collections.singletonList(map(
                    "type", "wait_for",
                    "expectation", map("type", "selector_exists", "selector", "#ready"),
                    "timeout_ms", 1000)),
                "expectations", Collections.singletonList(
                    map("type", "selector_exists", "selector", "#ready"))))));
    assertTrue(codes(structurallyValid.validationIssues())
        .contains("wait_for_without_interaction"));
  }

  @Test
  public void executionHashUsesNormalizedExecutableSemantics() {
    Map<String, Object> base = hashPlan("目标", "描述", " value ", null, null);
    Map<String, Object> explicitDefaults =
        hashPlan("另一个目标", "另一段描述", " value ", 30000L, 10000L);

    BrowserTestContractValidator.Report first = BrowserTestContractValidator.validate(base);
    BrowserTestContractValidator.Report second =
        BrowserTestContractValidator.validate(explicitDefaults);

    assertTrue(first.validationIssues().toString(), first.valid());
    assertTrue(second.validationIssues().toString(), second.valid());
    assertEquals(first.executionPlanHash(), second.executionPlanHash());
    assertEquals(64, first.executionPlanHash().length());

    Map<String, Object> changedInput = hashPlan("目标", "描述", "value", null, null);
    assertFalse(first.executionPlanHash().equals(
        BrowserTestContractValidator.validate(changedInput).executionPlanHash()));

    Map<String, Object> changedEntry = hashPlan("目标", "描述", " value ", null, null);
    changedEntry.put("entry_path", "/tmp/other.html");
    assertFalse(first.executionPlanHash().equals(
        BrowserTestContractValidator.validate(changedEntry).executionPlanHash()));
  }

  @Test
  public void executionHashCorrectlyEncodesQuotesBackslashesAndUnicode() {
    Map<String, Object> quoted = hashPlan("验证中文", "描述", "a\\\"b\\c", null, null);
    Map<String, Object> different = hashPlan("验证中文", "描述", "a\"b\\\\c", null, null);
    BrowserTestContractValidator.Report first = BrowserTestContractValidator.validate(quoted);
    BrowserTestContractValidator.Report second = BrowserTestContractValidator.validate(different);
    assertTrue(first.validationIssues().toString(), first.valid());
    assertTrue(second.validationIssues().toString(), second.valid());
    assertFalse(first.executionPlanHash().equals(second.executionPlanHash()));
  }

  private static Map<String, Object> hashPlan(
      String goal, String description, String input, Long transactionTimeout, Long waitTimeout) {
    Map<String, Object> wait = map(
        "type", "wait_for",
        "expectation", map(
            "type", "js_boolean",
            "expression", "document.body.dataset.ready === 'true'",
            "transition", "false_to_true"));
    if (waitTimeout != null) wait.put("timeout_ms", waitTimeout);
    Map<String, Object> plan = map(
        "entry_path", "/tmp/\"页面\\index.html",
        "goal", goal,
        "scenarios", Collections.singletonList(map(
            "id", "form",
            "description", description,
            "actions", Arrays.asList(
                map("type", "input", "selector", "#name", "value", input),
                wait),
            "expectations", Collections.singletonList(map(
                "type", "selector_exists",
                "selector", "#done",
                "transition", "eventually_true")))));
    if (transactionTimeout != null) plan.put("timeout_ms", transactionTimeout);
    return plan;
  }

  private static List<String> codes(List<Map<String, Object>> issues) {
    List<String> values = new ArrayList<>();
    for (Map<String, Object> issue : issues) values.add(String.valueOf(issue.get("code")));
    return values;
  }

  private static int countCode(List<Map<String, Object>> issues, String expected) {
    int count = 0;
    for (Map<String, Object> issue : issues) {
      if (expected.equals(String.valueOf(issue.get("code")))) count++;
    }
    return count;
  }

  private static Map<String, Object> issue(
      List<Map<String, Object>> issues, String expectedCode) {
    for (Map<String, Object> issue : issues) {
      if (expectedCode.equals(String.valueOf(issue.get("code")))) return issue;
    }
    return Collections.emptyMap();
  }

  private static int totalActions(List<Object> scenarios) {
    int total = 0;
    for (Object raw : scenarios) {
      total += ((List<?>) ((Map<?, ?>) raw).get("actions")).size();
    }
    return total;
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index + 1 < values.length; index += 2) {
      result.put(String.valueOf(values[index]), values[index + 1]);
    }
    return result;
  }
}
