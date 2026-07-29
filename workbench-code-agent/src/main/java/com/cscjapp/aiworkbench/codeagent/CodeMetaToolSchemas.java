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
            "基于当前 syntax/browser 证据提交结构化质量结论，不读取文件且不接收 path。通过时使用 "
                + "{\"passed\":true,\"blocking_gaps\":[],"
                + "\"minimal_version_risk\":false}。",
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
    properties.put("goal", limitedString("需要完成的任务目标。", 160));
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
            "targeted_edit",
            "single_file",
            "multi_file_modular",
            "staged_generation"));
    properties.put("planned_files", plannedFilesSchema());
    properties.put("verification_plan", limitedStringArray("完成前要执行的真实验证。", 6, 160));
    properties.put("implementation_shape", object("文件拆分和实现形态。"));
    properties.put("steps", planStepsSchema());
    properties.put("replan_reason", limitedString("真实证据变化后的短重规划原因。", 200));
    properties.put("self_review_required", bool("完成前是否要求结构化自查。"));
    properties.put("risks", arrayOfString("已识别的实现风险。"));
    return objectSchema(
        properties,
        Arrays.asList(
            "goal", "quality_mode", "writing_mode", "planned_files", "verification_plan", "steps"));
  }

  private static Map<String, Object> plannedFilesSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("path", limitedString("工作区内的预计文件路径。", 300));
    properties.put("action", enumeration("计划动作，不代表覆盖授权。", "create", "edit"));
    properties.put("purpose", limitedString("文件职责。", 120));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description",
        "确定会创建或修改的交付文件；不得填写备选文件，已有文件仍必须使用编辑工具。");
    schema.put("items", objectSchema(properties, Collections.singletonList("path")));
    schema.put("maxItems", 8);
    return schema;
  }

  private static Map<String, Object> planStepsSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", limitedString("稳定步骤 ID。", 80));
    properties.put("title", limitedString("短步骤标题。", 100));
    properties.put("phase", enumeration("步骤阶段。", "discover", "implement", "verify", "quality"));
    properties.put("required_tools", limitedStringArray("完成步骤需要的真实工具。", 4, 64));
    properties.put("acceptance", limitedStringArray("步骤验收条件。", 2, 120));
    properties.put("file_refs", limitedStringArray("关联 planned_files 的 file_id 或路径。", 8, 300));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", "复杂任务的 3 至 5 个核心步骤。");
    schema.put("items", objectSchema(
        properties, Arrays.asList("id", "title", "phase", "required_tools", "acceptance")));
    schema.put("minItems", 3);
    schema.put("maxItems", 5);
    return schema;
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
        Arrays.asList("passed", "blocking_gaps", "minimal_version_risk"));
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

  private static Map<String, Object> limitedString(String description, int maxLength) {
    Map<String, Object> schema = string(description);
    schema.put("maxLength", maxLength);
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

  private static Map<String, Object> limitedStringArray(
      String description, int maxItems, int maxLength) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", description);
    schema.put("items", limitedString("", maxLength));
    schema.put("maxItems", maxItems);
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
