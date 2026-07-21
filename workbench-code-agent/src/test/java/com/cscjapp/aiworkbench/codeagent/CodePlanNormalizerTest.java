package com.cscjapp.aiworkbench.codeagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class CodePlanNormalizerTest {
  @Test
  public void actionWinsOverNumericLegacyStep() {
    Map<String, Object> legacyStep = new LinkedHashMap<>();
    legacyStep.put("step", "1");
    legacyStep.put("action", "完整替换现有页面");
    Map<String, Object> raw = base();
    raw.put("steps", Collections.singletonList(legacyStep));

    Map<String, Object> normalized =
        normalizer(Collections.<String, Object>emptyMap()).normalize(raw);
    Map<?, ?> step = (Map<?, ?>) ((List<?>) normalized.get("steps")).get(0);

    assertEquals("完整替换现有页面", step.get("title"));
    assertEquals("step-1", step.get("id"));
    assertEquals(1, normalized.get("normalized_plan_version"));
  }

  @Test
  public void parsesSchemaObjectStringsIncludingHostExtensions() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("quality_bar", type("object"));
    properties.put("interface_design_spec", type("object"));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("properties", properties);
    Map<String, Object> raw = base();
    raw.put("quality_bar", "{\"runtime\":\"passes\"}");
    raw.put("interface_design_spec", "{\"layout\":\"mobile\"}");

    Map<String, Object> normalized = normalizer(schema).normalize(raw);

    assertTrue(normalized.get("quality_bar") instanceof Map);
    assertTrue(normalized.get("interface_design_spec") instanceof Map);
    assertEquals(
        "mobile", ((Map<?, ?>) normalized.get("interface_design_spec")).get("layout"));
  }

  @Test
  public void malformedJsonProducesWarningAndFallbackWithoutThrowing() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("quality_bar", type("object"));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("properties", properties);
    Map<String, Object> raw = base();
    raw.put("quality_bar", "{bad");

    Map<String, Object> normalized = normalizer(schema).normalize(raw);

    assertFalse(((List<?>) normalized.get("steps")).isEmpty());
    assertTrue(
        normalized.get("normalization_warnings").toString().contains("quality_bar_json_invalid"));
  }

  @Test
  public void canonicalPlanIsStableAndDoesNotMutateInput() {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("id", "verify");
    step.put("title", "运行真实验证");
    step.put("phase", "verify");
    step.put("required_tools", Arrays.asList("syntax_check", "browser_test"));
    step.put("acceptance", Collections.singletonList("全部通过"));
    Map<String, Object> raw = base();
    raw.put("steps", Collections.singletonList(step));

    Map<String, Object> normalized =
        normalizer(Collections.<String, Object>emptyMap()).normalize(raw);

    assertEquals(
        "运行真实验证", ((Map<?, ?>) ((List<?>) normalized.get("steps")).get(0)).get("title"));
    assertFalse(raw.containsKey("normalized_plan_version"));
    assertFalse(step.containsKey("status"));
  }

  private static Map<String, Object> base() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("goal", "完成任务");
    value.put("quality_mode", "standard");
    value.put("writing_mode", "targeted_edit");
    value.put("planned_files", Collections.singletonList("main.txt"));
    value.put("verification_plan", Collections.singletonList("syntax_check"));
    return value;
  }

  private static Map<String, Object> type(String name) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("type", name);
    return value;
  }

  private static CodePlanNormalizer normalizer(Map<String, Object> schema) {
    return new CodePlanNormalizer(schema);
  }
}
