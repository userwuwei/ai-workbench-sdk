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
    assertTrue(codes.contains("missing_false_to_true"));
    assertTrue(codes.contains("missing_scenario_id"));
    assertTrue(codes.contains("unexpected_scenario_id"));
    assertTrue(codes.contains("missing_dynamic_scenario"));
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
                      : "parseFloat(document.body.dataset.score) >= 0",
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
