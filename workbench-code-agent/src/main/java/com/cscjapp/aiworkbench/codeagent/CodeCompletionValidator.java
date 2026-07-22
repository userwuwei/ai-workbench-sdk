package com.cscjapp.aiworkbench.codeagent;

import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.TaskValidator;
import com.cscjapp.aiworkbench.api.ToolResult;
import com.cscjapp.aiworkbench.api.ValidationContext;
import com.cscjapp.aiworkbench.api.ValidationIssue;
import com.cscjapp.aiworkbench.api.ValidationResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class CodeCompletionValidator implements TaskValidator {
  private final CodeValidationContract contract;
  private final ManagedCodePlanCoordinator planCoordinator;

  CodeCompletionValidator(CodeValidationContract contract) {
    this(contract, null);
  }

  CodeCompletionValidator(
      CodeValidationContract contract, ManagedCodePlanCoordinator planCoordinator) {
    this.contract = contract;
    this.planCoordinator = planCoordinator;
  }

  @Override
  public Cancellable validate(ValidationContext context, Callback callback) {
    List<ValidationIssue> issues = new ArrayList<>();
    Map<String, Object> finalize =
        latest(context.evidence(), CodeAgentToolNames.FINALIZE_TASK);
    if (finalize == null && contract.finalizeEvidenceRequired()) {
      issues.add(
          blocker(
              "finalize_task_missing",
              "代码任务必须通过 finalize_task 明确结束，普通文本不能作为终态",
              Collections.emptyMap()));
      callback.onComplete(new ValidationResult(issues));
      return Cancellable.NONE;
    }

    String status = string(finalize == null ? null : finalize.get("status"), "completed");
    if (!"completed".equals(status)) {
      callback.onComplete(new ValidationResult(issues));
      return Cancellable.NONE;
    }
    String completionType =
        string(finalize == null ? null : finalize.get("completion_type"), "code_generation");
    if (planCoordinator != null) {
      boolean planRequired = planCoordinator.mode() == CodePlanningMode.FORCE
          || (planCoordinator.mode() != CodePlanningMode.SKIP
              && contract.requiresManagedPlan(completionType));
      if (planRequired && !planCoordinator.hasPlan()) {
        issues.add(blocker(
            "managed_plan_missing",
            "当前复杂代码任务必须先通过 plan_task 建立短计划",
            Collections.emptyMap()));
      } else if (planCoordinator.hasPlan() && !planCoordinator.isComplete()) {
        issues.add(blocker(
            "managed_plan_incomplete",
            "受管计划仍缺少真实工具证据，不能标记 completed",
            Collections.emptyMap()));
      }
    }
    for (String operation : contract.requiredEvidence(completionType)) {
      if (planCoordinator != null
          && planCoordinator.hasPlan()
          && !planCoordinator.hasCurrentEvidence(operation)) {
        issues.add(
            blocker(
                operation + "_missing",
                "受管计划当前版本缺少真实 " + operation + " 证据",
                Collections.emptyMap()));
        continue;
      }
      Map<String, Object> evidence = latest(context.evidence(), operation);
      if (evidence == null) {
        issues.add(
            blocker(
                operation + "_missing",
                "任务完成前必须取得真实 " + operation + " 证据",
                Collections.emptyMap()));
      } else if (Boolean.FALSE.equals(evidence.get("passed"))) {
        issues.add(
            blocker(operation + "_failed", operation + " 未通过，必须修复后重试", evidence));
      }
    }

    Map<String, Object> quality =
        latest(context.evidence(), CodeAgentToolNames.QUALITY_REVIEW);
    boolean requiresQuality = contract.requiresQualityReview(completionType)
        || (planCoordinator != null
            && "interface_product".equals(planCoordinator.qualityMode()));
    boolean currentPlanQualityMissing = requiresQuality
        && planCoordinator != null
        && planCoordinator.hasPlan()
        && !planCoordinator.hasCurrentEvidence(CodeAgentToolNames.QUALITY_REVIEW);
    if (currentPlanQualityMissing) {
      issues.add(
          blocker(
              "quality_review_missing",
              "受管计划当前版本缺少通过的 quality_review 证据",
              Collections.emptyMap()));
    } else if (requiresQuality && quality == null) {
      issues.add(
          blocker(
              "quality_review_missing",
              "当前任务类型完成前必须执行 quality_review",
              Collections.emptyMap()));
    } else if (quality != null
        && (Boolean.FALSE.equals(quality.get("passed"))
            || Boolean.TRUE.equals(quality.get("minimal_version_risk"))
            || nonEmptyList(quality.get("blocking_gaps"))
            || nonEmptyList(quality.get("claimed_but_unsupported")))) {
      issues.add(
          blocker(
              "quality_review_failed",
              "质量自查仍存在阻塞项、最低可用风险或无证据声明",
              quality));
    } else if (requiresQuality
        && !Boolean.TRUE.equals(quality.get("passed"))) {
      issues.add(
          blocker(
              "quality_review_failed",
              "quality_review 必须明确返回 passed=true",
              quality));
    }
    callback.onComplete(new ValidationResult(issues));
    return Cancellable.NONE;
  }

  private static Map<String, Object> latest(List<ToolResult> evidence, String operation) {
    for (int index = evidence.size() - 1; index >= 0; index--) {
      Map<String, Object> data = evidence.get(index).data();
      if (operation.equals(data.get("operation"))) return data;
    }
    return null;
  }

  private static ValidationIssue blocker(
      String code, String message, Map<String, Object> evidence) {
    return new ValidationIssue(
        code,
        message,
        ValidationIssue.Severity.BLOCKER,
        evidence == null ? Collections.emptyMap() : evidence);
  }

  private static String string(Object value, String fallback) {
    return value == null || String.valueOf(value).trim().isEmpty()
        ? fallback
        : String.valueOf(value);
  }

  private static boolean nonEmptyList(Object value) {
    return value instanceof List && !((List<?>) value).isEmpty();
  }
}
