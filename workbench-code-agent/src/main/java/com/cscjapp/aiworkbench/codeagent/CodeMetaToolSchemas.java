package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.ToolSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CodeMetaToolSchemas {
  private CodeMetaToolSchemas() {}

  static List<ToolSpec> create(Map<String, Map<String, Object>> extensions) {
    List<ToolSpec> result = new ArrayList<>();
    result.add(
        spec(
            CodeAgentToolNames.PLAN_TASK,
            "为复杂代码任务提交短计划、质量目标和验证策略；只规划，不执行项目操作。",
            planSchema(),
            extensions));
    result.add(
        spec(
            CodeAgentToolNames.QUALITY_REVIEW,
            "根据需求、计划和真实工具证据提交结构化质量自查；有阻塞项时继续修改。",
            qualitySchema(),
            extensions));
    result.add(
        spec(
            CodeAgentToolNames.FINALIZE_TASK,
            "结束当前代码任务。完成、阻塞或需要用户输入时必须调用。",
            finalizeSchema(),
            extensions));
    return Collections.unmodifiableList(result);
  }

  private static ToolSpec spec(
      String name,
      String description,
      Map<String, Object> base,
      Map<String, Map<String, Object>> extensions) {
    Map<String, Object> extension =
        extensions == null ? null : extensions.get(name);
    return new ToolSpec(name, description, SchemaMaps.merge(base, extension));
  }

  private static Map<String, Object> planSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "task_type",
        enumeration(
            "任务类型。",
            "bugfix",
            "feature",
            "refactor",
            "module_integration",
            "explain",
            "batch_edit"));
    properties.put("goal", string("需要完成的任务目标。"));
    properties.put("task_summary", string("对用户需求和约束的简短理解。"));
    properties.put(
        "quality_mode",
        enumeration(
            "本次质量模式。",
            "standard",
            "interface_product"));
    properties.put("quality_bar", object("可验证的短质量契约。"));
    properties.put("quality_gap", object("现状与质量目标之间的差距。"));
    properties.put("deliverable_evidence", arrayOfString("交付时必须具备的真实证据。"));
    properties.put(
        "writing_mode",
        enumeration(
            "计划采用的写入形态。",
            "single_file",
            "multi_file_modular",
            "staged_generation",
            "targeted_edit"));
    properties.put("planned_files", arrayOfString("预计创建或修改的文件。"));
    properties.put("verification_plan", arrayOfString("完成前要执行的真实验证。"));
    properties.put("implementation_shape", object("文件拆分和实现形态。"));
    properties.put("steps", arrayOfObject("短执行步骤。"));
    properties.put("self_review_required", bool("完成前是否要求结构化自查。"));
    properties.put("risks", arrayOfString("已识别的实现风险。"));
    return objectSchema(properties, Arrays.asList("goal", "quality_mode"));
  }

  private static Map<String, Object> qualitySchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("review_target", string("本次自查目标。"));
    properties.put("against_quality_bar", bool("是否逐项对照需求和质量契约。"));
    properties.put(
        "quality_mode",
        enumeration("本次质量模式。", "standard", "interface_product"));
    properties.put("passed", bool("真实证据是否支持当前任务完成。"));
    properties.put("blocking_gaps", arrayOfString("仍未解决的阻塞问题。"));
    properties.put("minor_issues", arrayOfString("不阻塞完成的次要问题。"));
    properties.put("suggestions", arrayOfString("后续可选改进建议。"));
    properties.put("dimension_reviews", object("按质量维度给出的短检查结论。"));
    properties.put("minimal_version_risk", bool("是否仍存在只完成最低可用版本的风险。"));
    properties.put("claimed_but_unsupported", arrayOfString("缺少真实证据的声明。"));
    properties.put("effect_claims_review", arrayOfObject("对效果声明及其证据的检查。"));
    properties.put("evidence", arrayOfString("实际检查过的文件或工具结果。"));
    properties.put("evidence_checked", arrayOfString("实际检查过的证据。"));
    properties.put("improvement_plan", arrayOfString("不能完成时下一步真实修改计划。"));
    properties.put("polish_decision", string("体验打磨已完成、已集成或暂缓的结论。"));
    properties.put(
        "experience_polish_status",
        enumeration(
            "体验打磨状态。",
            "separate_pass_done",
            "integrated_in_implementation",
            "not_done"));
    properties.put("experience_polish_evidence", arrayOfString("体验打磨的真实证据。"));
    properties.put("selected_improvements", arrayOfString("已选择实施的改进。"));
    properties.put("deferred_improvements", arrayOfString("明确暂缓的改进。"));
    properties.put("defer_reason", string("暂缓原因。"));
    properties.put("verification_summary", string("真实验证结果摘要。"));
    return objectSchema(
        properties,
        Arrays.asList(
            "against_quality_bar",
            "quality_mode",
            "passed",
            "blocking_gaps",
            "minimal_version_risk"));
  }

  private static Map<String, Object> finalizeSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "status",
        enumeration("当前任务终态。", "completed", "blocked", "needs_user_input"));
    properties.put(
        "completion_type",
        enumeration(
            "completed 时的任务类型。",
            "explain",
            "simple_fix",
            "code_generation",
            "feature_integration",
            "ui_product",
            "refactor"));
    properties.put("summary", string("面向用户的最终结果摘要。"));
    properties.put("changed_files", arrayOfString("实际创建或修改过的文件路径。"));
    properties.put("runnable_entry_path", string("可运行入口路径；不适用时留空。"));
    properties.put("verification", arrayOfString("已经执行过的验证结论。"));
    properties.put("closure_evidence", object("触发、状态、执行和效果的闭环证据。"));
    properties.put("quality_review", object("quality_review 的短摘要。"));
    properties.put("question", string("需要用户输入时提出的唯一明确问题。"));
    return objectSchema(properties, Arrays.asList("status", "summary"));
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", new ArrayList<>(required));
    schema.put("additionalProperties", false);
    return schema;
  }

  private static Map<String, Object> string(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    schema.put("description", description);
    return schema;
  }

  private static Map<String, Object> bool(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "boolean");
    schema.put("description", description);
    return schema;
  }

  private static Map<String, Object> object(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("description", description);
    return schema;
  }

  private static Map<String, Object> arrayOfString(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", description);
    schema.put("items", Collections.singletonMap("type", "string"));
    return schema;
  }

  private static Map<String, Object> arrayOfObject(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", description);
    schema.put("items", Collections.singletonMap("type", "object"));
    return schema;
  }

  private static Map<String, Object> enumeration(String description, String... values) {
    Map<String, Object> schema = string(description);
    schema.put("enum", Arrays.asList(values));
    return schema;
  }
}
