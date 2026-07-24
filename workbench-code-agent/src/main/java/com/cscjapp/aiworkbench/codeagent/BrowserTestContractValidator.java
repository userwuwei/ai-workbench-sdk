package com.cscjapp.aiworkbench.codeagent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, deterministic validation for the model-facing browser_test transaction contract. */
public final class BrowserTestContractValidator {
  public static final int MAX_SCENARIOS = 6;
  public static final int MAX_ACTIONS_PER_SCENARIO = 20;
  public static final int MAX_ACTIONS_PER_TRANSACTION = 60;
  public static final int MAX_EXPECTATIONS_PER_SCENARIO = 6;
  public static final long DEFAULT_WAIT_FOR_TIMEOUT_MS = 10_000L;
  public static final long MIN_WAIT_FOR_TIMEOUT_MS = 500L;
  public static final long MAX_WAIT_FOR_TIMEOUT_MS = 60_000L;

  private static final Pattern SCENARIO_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern ASSIGNMENT = Pattern.compile("(?<![=!<>])=(?!=|>)");
  private static final Pattern MUTATING_TOKEN = Pattern.compile(
      "(?i)(\\+\\+|--|\\b(?:delete|new|await|yield|fetch|eval|Function|setTimeout|setInterval|requestAnimationFrame)\\b"
          + "|\\.(?:click|focus|blur|submit|reset|remove|append|prepend|replaceWith|setAttribute|removeAttribute|dispatchEvent|insertAdjacentHTML|insertAdjacentElement|write|writeln|play|pause|setItem|removeItem|clear)\\s*\\()");
  private static final Pattern FUNCTION_CALL =
      Pattern.compile("(?:^|[^.$A-Za-z0-9_])([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
  private static final Pattern METHOD_CALL =
      Pattern.compile("\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
  private static final Set<String> READ_ONLY_FUNCTIONS = set(
      "Boolean", "String", "Number", "parseInt", "parseFloat");
  private static final Set<String> READ_ONLY_METHODS = set(
      "querySelector", "querySelectorAll", "getElementById", "getElementsByClassName",
      "getElementsByTagName", "getAttribute", "hasAttribute", "matches", "closest",
      "contains", "includes", "startsWith", "endsWith", "indexOf", "some", "every",
      "find", "filter", "map", "getItem");
  private static final Set<String> LEGACY_TOP_LEVEL_FIELDS = set(
      "operation", "steps", "interaction_check_ids", "target_url");

  private BrowserTestContractValidator() {}

  public static Report validate(Map<String, ?> arguments) {
    return validate(arguments, Collections.<String>emptyList(), false);
  }

  public static Report validate(
      Map<String, ?> arguments, Collection<String> requiredScenarioIds, boolean interactionRequired) {
    Validator validator = new Validator(arguments, requiredScenarioIds, interactionRequired);
    validator.validate();
    return validator.report();
  }

  public static void requireReadOnlyExpression(String source) {
    String error = readOnlyExpressionError(source);
    if (!error.isEmpty()) throw new IllegalArgumentException(error);
  }

  public static String readOnlyExpressionError(String source) {
    String script = source == null ? "" : source.trim();
    if (script.isEmpty()) return "check_id evaluate_js 缺少布尔表达式";
    if (script.length() > 2000 || script.indexOf(';') >= 0
        || script.indexOf('{') >= 0 || script.indexOf('}') >= 0
        || script.indexOf('`') >= 0 || script.contains("=>")) {
      return "check_id evaluate_js 只允许无副作用的只读布尔表达式";
    }
    String code;
    try {
      code = maskQuotedStrings(script);
    } catch (IllegalArgumentException error) {
      return error.getMessage();
    }
    if (code.indexOf('[') >= 0
        || code.indexOf(']') >= 0
        || code.indexOf(',') >= 0
        || code.contains("?.(")
        || ASSIGNMENT.matcher(code).find()
        || MUTATING_TOKEN.matcher(code).find()) {
      return "check_id evaluate_js 只允许无副作用的只读布尔表达式";
    }
    Matcher function = FUNCTION_CALL.matcher(code);
    while (function.find()) {
      if (!READ_ONLY_FUNCTIONS.contains(function.group(1))) {
        return "check_id evaluate_js 不允许调用函数: " + function.group(1);
      }
    }
    Matcher method = METHOD_CALL.matcher(code);
    while (method.find()) {
      if (!READ_ONLY_METHODS.contains(method.group(1))) {
        return "check_id evaluate_js 不允许调用方法: " + method.group(1);
      }
    }
    return "";
  }

  public static final class Report {
    private final List<Issue> issues;
    private final List<String> providedScenarioIds;
    private final boolean hasDynamicScenario;

    private Report(
        List<Issue> issues, List<String> providedScenarioIds, boolean hasDynamicScenario) {
      this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
      this.providedScenarioIds =
          Collections.unmodifiableList(new ArrayList<>(providedScenarioIds));
      this.hasDynamicScenario = hasDynamicScenario;
    }

    public boolean valid() {
      return issues.isEmpty();
    }

    public String firstMessage() {
      return issues.isEmpty() ? "" : issues.get(0).message;
    }

    public List<Issue> issues() {
      return issues;
    }

    public List<Map<String, Object>> validationIssues() {
      List<Map<String, Object>> values = new ArrayList<>();
      for (Issue issue : issues) values.add(issue.toMap());
      return values;
    }

    public List<String> providedScenarioIds() {
      return providedScenarioIds;
    }

    public boolean hasDynamicScenario() {
      return hasDynamicScenario;
    }
  }

  public static final class Issue {
    public final String path;
    public final String code;
    public final String message;
    public final Object actual;
    public final Object allowed;

    private Issue(String path, String code, String message, Object actual, Object allowed) {
      this.path = path;
      this.code = code;
      this.message = message;
      this.actual = actual;
      this.allowed = allowed;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("path", path);
      value.put("code", code);
      if (actual != null) value.put("actual", actual);
      if (allowed != null) {
        value.put("allowed", allowed);
        if (allowed instanceof Number && code.startsWith("too_many")) {
          value.put("maximum", allowed);
        }
      }
      value.put("message", message);
      return value;
    }
  }

  private static final class Validator {
    private final Map<String, ?> arguments;
    private final LinkedHashSet<String> requiredIds = new LinkedHashSet<>();
    private final List<Issue> issues = new ArrayList<>();
    private final List<String> providedIds = new ArrayList<>();
    private final LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> duplicateIds = new LinkedHashSet<>();
    private final Map<String, String> behaviorOwners = new LinkedHashMap<>();
    private final boolean interactionRequired;
    private boolean hasDynamicScenario;
    private int totalActions;

    Validator(
        Map<String, ?> arguments,
        Collection<String> requiredScenarioIds,
        boolean interactionRequired) {
      this.arguments = arguments;
      if (requiredScenarioIds != null) {
        for (String id : requiredScenarioIds) if (id != null && !id.trim().isEmpty()) {
          requiredIds.add(id.trim());
        }
      }
      this.interactionRequired = interactionRequired;
    }

    void validate() {
      if (arguments == null) {
        issue("browser_test", "invalid_type", "browser_test 参数必须是对象", null, "object");
        return;
      }
      onlyKeys(arguments, "browser_test", set("entry_path", "goal", "scenarios", "timeout_ms"));
      requiredString(arguments.get("goal"), "goal", 500);
      optionalString(arguments.get("entry_path"), "entry_path", 2000);
      validateTimeout(arguments.get("timeout_ms"));
      Object rawScenarios = arguments.get("scenarios");
      if (!(rawScenarios instanceof List)) {
        issue("scenarios", "invalid_type", "browser_test.scenarios 必填且必须是数组",
            typeName(rawScenarios), "array");
        return;
      }
      List<?> scenarios = (List<?>) rawScenarios;
      if (scenarios.size() < 1 || scenarios.size() > MAX_SCENARIOS) {
        issue("scenarios", "invalid_count", "browser_test.scenarios 数量必须为 1～6",
            scenarios.size(), "1.." + MAX_SCENARIOS);
      }
      // Keep validation exhaustive even when the scenario count itself is invalid. The caller
      // will reject the transaction before launch, so inspecting every supplied item is safe and
      // lets one zero-launch correction address all deterministic contract problems at once.
      for (int index = 0; index < scenarios.size(); index++) {
        validateScenario(scenarios.get(index), index);
      }
      if (totalActions > MAX_ACTIONS_PER_TRANSACTION) {
        issue("scenarios", "too_many_total_actions", "browser_test 全部场景 actions 总数不能超过 60",
            totalActions, MAX_ACTIONS_PER_TRANSACTION);
      }
      validatePlanIds();
      if (interactionRequired && !hasDynamicScenario) {
        issue("scenarios", "missing_dynamic_scenario",
            "交互页面至少需要一个包含 actions 和 false_to_true 断言的场景。",
            false, true);
      }
    }

    private void validateScenario(Object raw, int scenarioIndex) {
      String path = "scenarios[" + scenarioIndex + "]";
      if (!(raw instanceof Map)) {
        issue(path, "invalid_type", path + " 必须是对象", typeName(raw), "object");
        return;
      }
      Map<?, ?> scenario = (Map<?, ?>) raw;
      onlyKeys(scenario, path, set("id", "description", "actions", "expectations"));
      String id = requiredString(scenario.get("id"), path + ".id", 64);
      if (!id.isEmpty()) {
        providedIds.add(id);
        if (!SCENARIO_ID.matcher(id).matches()) {
          issue(path + ".id", "invalid_format",
              path + ".id 只能包含字母、数字、点、下划线和连字符，且长度不超过 64",
              id, "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        }
        if (!uniqueIds.add(id)) duplicateIds.add(id);
      }
      requiredString(scenario.get("description"), path + ".description", 300);

      Object rawActions = scenario.get("actions");
      List<?> actions = rawActions instanceof List ? (List<?>) rawActions : Collections.emptyList();
      if (!(rawActions instanceof List)) {
        issue(path + ".actions", "invalid_type", path + ".actions 必填且必须是数组",
            typeName(rawActions), "array");
      } else if (actions.size() > MAX_ACTIONS_PER_SCENARIO) {
        issue(path + ".actions", "too_many_actions", path + ".actions 数量不能超过 20",
            actions.size(), MAX_ACTIONS_PER_SCENARIO);
      }
      totalActions += actions.size();
      boolean hasUserAction = false;
      for (int index = 0; index < actions.size(); index++) {
        hasUserAction |= validateAction(actions.get(index), path + ".actions[" + index + "]");
      }

      Object rawExpectations = scenario.get("expectations");
      List<?> expectations = rawExpectations instanceof List
          ? (List<?>) rawExpectations : Collections.emptyList();
      if (!(rawExpectations instanceof List)) {
        issue(path + ".expectations", "invalid_type",
            path + ".expectations 必填且必须是数组", typeName(rawExpectations), "array");
      } else if (expectations.isEmpty()
          || expectations.size() > MAX_EXPECTATIONS_PER_SCENARIO) {
        issue(path + ".expectations", "invalid_count",
            path + ".expectations 数量必须为 1～6", expectations.size(), "1..6");
      }
      boolean dynamic = false;
      for (int index = 0; index < expectations.size(); index++) {
        dynamic |= validateExpectation(
            expectations.get(index), path + ".expectations[" + index + "]", !hasUserAction);
      }
      if (!actions.isEmpty() && !hasUserAction) {
        issue(path + ".actions", "wait_for_without_interaction",
            path + " 仅包含 wait_for；无用户操作时请改用 eventually_true expectations",
            false, "click/input");
      }
      if (hasUserAction && !dynamic) {
        issue(path + ".expectations", "missing_false_to_true",
            path + " 包含 click/input 时至少需要一个 false_to_true expectation", false, true);
      }
      if (hasUserAction && dynamic) {
        hasDynamicScenario = true;
      }
      if (!id.isEmpty() && rawActions instanceof List && rawExpectations instanceof List) {
        String fingerprint = canonicalValue(normalizedActions(actions)) + "|"
            + canonicalValue(normalizedExpectations(expectations));
        String owner = behaviorOwners.get(fingerprint);
        if (owner == null) {
          behaviorOwners.put(fingerprint, id);
        } else if (!owner.equals(id)) {
          issue(path, "duplicate_scenario_behavior",
              "不同 scenario.id 不得复用完全相同的 actions + expectations: "
                  + owner + " / " + id,
              Arrays.asList(owner, id), "distinct behavior");
        }
      }
    }

    private boolean validateAction(Object raw, String path) {
      if (!(raw instanceof Map)) {
        issue(path, "invalid_type", path + " 必须是对象", typeName(raw), "object");
        return false;
      }
      Map<?, ?> action = (Map<?, ?>) raw;
      String type = requiredString(action.get("type"), path + ".type", 32);
      if ("click".equals(type) || "input".equals(type)) {
        onlyKeys(action, path, "input".equals(type)
            ? set("type", "selector", "value") : set("type", "selector"));
        requiredString(action.get("selector"), path + ".selector", 500);
        if ("input".equals(type)) requiredString(action.get("value"), path + ".value", 2000);
        return true;
      }
      if ("wait_for".equals(type)) {
        onlyKeys(action, path, set("type", "expectation", "timeout_ms"));
        validateExpectation(action.get("expectation"), path + ".expectation", false);
        validateWaitForTimeout(action.get("timeout_ms"), path + ".timeout_ms");
        return false;
      }
      onlyKeys(action, path, set("type", "selector", "value", "expectation", "timeout_ms"));
      issue(path + ".type", "unsupported_action", path + ".type 仅支持 click/input/wait_for",
          type, Arrays.asList("click", "input", "wait_for"));
      return false;
    }

    private boolean validateExpectation(Object raw, String path, boolean staticScenario) {
      if (!(raw instanceof Map)) {
        issue(path, "invalid_type", path + " 必须是对象", typeName(raw), "object");
        return false;
      }
      Map<?, ?> expectation = (Map<?, ?>) raw;
      onlyKeys(expectation, path, set("type", "text", "selector", "expression", "transition"));
      String type = requiredString(expectation.get("type"), path + ".type", 32);
      String transition = optionalString(expectation.get("transition"), path + ".transition", 32);
      if (transition.isEmpty()) transition = "eventually_true";
      boolean dynamic = "false_to_true".equals(transition);
      if (!"eventually_true".equals(transition) && !dynamic) {
        issue(path + ".transition", "unsupported_transition",
            path + ".transition 仅支持 eventually_true/false_to_true", transition,
            Arrays.asList("eventually_true", "false_to_true"));
      }
      if (staticScenario && dynamic) {
        issue(path + ".transition", "dynamic_without_actions",
            path + " 静态场景不能使用 false_to_true", transition, "eventually_true");
      }
      if ("text_visible".equals(type)
          || "url_contains".equals(type)
          || "title_contains".equals(type)) {
        requiredString(expectation.get("text"), path + ".text", 1000);
      } else if ("selector_exists".equals(type)) {
        requiredString(expectation.get("selector"), path + ".selector", 500);
      } else if ("js_boolean".equals(type)) {
        String expression = requiredString(expectation.get("expression"), path + ".expression", 2000);
        String error = readOnlyExpressionError(expression);
        if (!expression.isEmpty() && !error.isEmpty()) {
          issue(path + ".expression", "invalid_readonly_expression",
              path + ".expression 无效: " + error, expression, "只读布尔表达式");
        }
      } else if (!type.isEmpty()) {
        issue(path + ".type", "unsupported_expectation",
            path + ".type 不受支持: " + type, type,
            Arrays.asList("text_visible", "selector_exists", "url_contains",
                "title_contains", "js_boolean"));
      }
      return dynamic;
    }

    private void validatePlanIds() {
      for (String duplicate : duplicateIds) {
        issue("scenarios", "duplicate_scenario_id", "browser_test scenario.id 不得重复: " + duplicate,
            duplicate, "unique");
      }
      if (requiredIds.isEmpty()) return;
      for (String required : requiredIds) if (!uniqueIds.contains(required)) {
        issue("scenarios", "missing_scenario_id", "缺少计划要求的 browser_test scenario.id: " + required,
            required, new ArrayList<>(requiredIds));
      }
      for (String provided : uniqueIds) if (!requiredIds.contains(provided)) {
        issue("scenarios", "unexpected_scenario_id", "包含计划之外的 browser_test scenario.id: " + provided,
            provided, new ArrayList<>(requiredIds));
      }
    }

    private void validateWaitForTimeout(Object raw, String path) {
      if (raw == null) return;
      if (!(raw instanceof Number)) {
        issue(path, "invalid_type", path + " 必须是整数", typeName(raw), "integer");
        return;
      }
      double numeric = ((Number) raw).doubleValue();
      long value = ((Number) raw).longValue();
      if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
        issue(path, "invalid_type", path + " 必须是整数", raw, "integer");
      } else if (value < MIN_WAIT_FOR_TIMEOUT_MS || value > MAX_WAIT_FOR_TIMEOUT_MS) {
        issue(path, "out_of_range", path + " 必须在 500～60000 之间",
            value, MIN_WAIT_FOR_TIMEOUT_MS + ".." + MAX_WAIT_FOR_TIMEOUT_MS);
      }
    }

    private void validateTimeout(Object raw) {
      if (raw == null) return;
      if (!(raw instanceof Number)) {
        issue("timeout_ms", "invalid_type", "browser_test.timeout_ms 必须是整数",
            typeName(raw), "integer");
        return;
      }
      long value = ((Number) raw).longValue();
      double numeric = ((Number) raw).doubleValue();
      if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
        issue("timeout_ms", "invalid_type", "browser_test.timeout_ms 必须是整数",
            raw, "integer");
        return;
      }
      if (value < 3000L || value > 120000L) {
        issue("timeout_ms", "out_of_range", "browser_test.timeout_ms 必须在 3000～120000 之间",
            value, "3000..120000");
      }
    }

    private String requiredString(Object raw, String path, int maxLength) {
      String value = optionalString(raw, path, maxLength);
      if (value.isEmpty()) issue(path, "required", path + " 必填且不能为空", value, "non-empty string");
      return value;
    }

    private String optionalString(Object raw, String path, int maxLength) {
      if (raw == null) return "";
      if (!(raw instanceof String)) {
        issue(path, "invalid_type", path + " 必须是字符串", typeName(raw), "string");
        return "";
      }
      String value = ((String) raw).trim();
      if (value.length() > maxLength) {
        issue(path, "too_long", path + " 长度不能超过 " + maxLength, value.length(), maxLength);
      }
      return value;
    }

    private void onlyKeys(Map<?, ?> value, String path, Set<String> allowed) {
      for (Object rawKey : value.keySet()) {
        String key = String.valueOf(rawKey);
        if (!allowed.contains(key)) {
          if ("browser_test".equals(path) && LEGACY_TOP_LEVEL_FIELDS.contains(key)) {
            issue(path + "." + key, "legacy_parameter",
                "browser_test 2.0 已移除旧参数 " + key
                    + "；请改用 goal + entry_path + scenarios 声明一次性验证事务",
                key, new ArrayList<>(allowed));
          } else {
            issue(path + "." + key, "unsupported_field", path + " 包含不支持的参数: " + key,
                key, new ArrayList<>(allowed));
          }
        }
      }
    }

    private void issue(String path, String code, String message, Object actual, Object allowed) {
      issues.add(new Issue(path, code, message, actual, allowed));
    }

    Report report() {
      return new Report(issues, providedIds, hasDynamicScenario);
    }
  }

  private static String canonicalValue(Object raw) {
    if (raw == null) return "null";
    if (raw instanceof Map) {
      StringBuilder out = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, Object> entry : new TreeMap<>(stringKeyMap((Map<?, ?>) raw)).entrySet()) {
        if (!first) out.append(',');
        first = false;
        out.append(entry.getKey()).append(':').append(canonicalValue(entry.getValue()));
      }
      return out.append('}').toString();
    }
    if (raw instanceof Collection) {
      StringBuilder out = new StringBuilder("[");
      boolean first = true;
      for (Object item : (Collection<?>) raw) {
        if (!first) out.append(',');
        first = false;
        out.append(canonicalValue(item));
      }
      return out.append(']').toString();
    }
    if (raw instanceof String) return "\"" + raw + "\"";
    return String.valueOf(raw);
  }

  private static List<Object> normalizedActions(List<?> actions) {
    List<Object> out = new ArrayList<>();
    for (Object raw : actions) {
      if (!(raw instanceof Map)) {
        out.add(raw);
        continue;
      }
      Map<?, ?> action = (Map<?, ?>) raw;
      Map<String, Object> value = new LinkedHashMap<>();
      String type = trimText(action.get("type"));
      value.put("type", type);
      if ("click".equals(type) || "input".equals(type)) {
        value.put("selector", trimText(action.get("selector")));
        if ("input".equals(type)) value.put("value", trimText(action.get("value")));
      } else if ("wait_for".equals(type)) {
        Object expectation = action.get("expectation");
        value.put("expectation", expectation instanceof Map
            ? normalizedExpectation((Map<?, ?>) expectation) : expectation);
        value.put("timeout_ms", normalizedInteger(
            action.get("timeout_ms"), DEFAULT_WAIT_FOR_TIMEOUT_MS));
      }
      out.add(value);
    }
    return out;
  }

  private static List<Object> normalizedExpectations(List<?> expectations) {
    List<Object> out = new ArrayList<>();
    for (Object raw : expectations) {
      out.add(raw instanceof Map ? normalizedExpectation((Map<?, ?>) raw) : raw);
    }
    return out;
  }

  private static Map<String, Object> normalizedExpectation(Map<?, ?> expectation) {
    Map<String, Object> value = new LinkedHashMap<>();
    String type = trimText(expectation.get("type"));
    value.put("type", type);
    String transition = trimText(expectation.get("transition"));
    value.put("transition", transition.isEmpty() ? "eventually_true" : transition);
    if ("selector_exists".equals(type)) {
      value.put("selector", trimText(expectation.get("selector")));
    } else if ("js_boolean".equals(type)) {
      value.put("expression", trimText(expectation.get("expression")));
    } else {
      value.put("text", trimText(expectation.get("text")));
    }
    return value;
  }

  private static String trimText(Object raw) {
    return raw instanceof String ? ((String) raw).trim() : String.valueOf(raw == null ? "" : raw);
  }

  private static Object normalizedInteger(Object raw, long fallback) {
    if (!(raw instanceof Number)) return raw == null ? fallback : raw;
    double numeric = ((Number) raw).doubleValue();
    return Double.isFinite(numeric) && numeric == Math.rint(numeric)
        ? ((Number) raw).longValue() : raw;
  }

  private static Map<String, Object> stringKeyMap(Map<?, ?> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      out.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return out;
  }

  private static String maskQuotedStrings(String source) {
    StringBuilder out = new StringBuilder(source.length());
    char quote = 0;
    boolean escaped = false;
    for (int index = 0; index < source.length(); index++) {
      char value = source.charAt(index);
      if (quote == 0) {
        if (value == '\'' || value == '"') {
          quote = value;
          out.append(' ');
        } else {
          out.append(value);
        }
        continue;
      }
      out.append(' ');
      if (escaped) escaped = false;
      else if (value == '\\') escaped = true;
      else if (value == quote) quote = 0;
    }
    if (quote != 0) throw new IllegalArgumentException("check_id evaluate_js 字符串未闭合");
    return out.toString();
  }

  private static String typeName(Object value) {
    if (value == null) return "null";
    if (value instanceof Map) return "object";
    if (value instanceof List) return "array";
    if (value instanceof String) return "string";
    if (value instanceof Number) return "number";
    if (value instanceof Boolean) return "boolean";
    return value.getClass().getSimpleName();
  }

  private static LinkedHashSet<String> set(String... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }
}
