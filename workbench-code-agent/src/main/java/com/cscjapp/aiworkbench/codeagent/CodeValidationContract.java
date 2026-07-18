package com.cscjapp.aiworkbench.codeagent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declares the real evidence required by each code-task completion type. */
public final class CodeValidationContract {
  private final Map<String, List<String>> evidenceByType;
  private final List<String> defaultEvidence;
  private final Set<String> evidenceExemptTypes;
  private final Set<String> qualityReviewTypes;
  private final boolean finalizeEvidenceRequired;

  private CodeValidationContract(Builder builder) {
    Map<String, List<String>> copied = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : builder.evidenceByType.entrySet()) {
      copied.put(entry.getKey(), immutableNames(entry.getValue()));
    }
    evidenceByType = Collections.unmodifiableMap(copied);
    defaultEvidence = immutableNames(builder.defaultEvidence);
    evidenceExemptTypes =
        Collections.unmodifiableSet(new LinkedHashSet<>(builder.evidenceExemptTypes));
    qualityReviewTypes =
        Collections.unmodifiableSet(new LinkedHashSet<>(builder.qualityReviewTypes));
    finalizeEvidenceRequired = builder.finalizeEvidenceRequired;
  }

  public static Builder builder() {
    return new Builder();
  }

  public List<String> requiredEvidence(String completionType) {
    String type = safe(completionType);
    if (evidenceExemptTypes.contains(type)) return Collections.emptyList();
    List<String> exact = evidenceByType.get(type);
    return exact == null ? defaultEvidence : exact;
  }

  public boolean requiresQualityReview(String completionType) {
    return qualityReviewTypes.contains(safe(completionType));
  }

  public boolean finalizeEvidenceRequired() {
    return finalizeEvidenceRequired;
  }

  private static List<String> immutableNames(List<String> values) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        String name = safe(value);
        if (!name.isEmpty()) names.add(name);
      }
    }
    return Collections.unmodifiableList(new ArrayList<>(names));
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }

  public static final class Builder {
    private final Map<String, List<String>> evidenceByType = new LinkedHashMap<>();
    private final List<String> defaultEvidence = new ArrayList<>();
    private final Set<String> evidenceExemptTypes = new LinkedHashSet<>();
    private final Set<String> qualityReviewTypes = new LinkedHashSet<>();
    private boolean finalizeEvidenceRequired = true;

    public Builder defaultRequiredEvidence(String... operations) {
      defaultEvidence.clear();
      if (operations != null) defaultEvidence.addAll(Arrays.asList(operations));
      return this;
    }

    public Builder requireEvidence(String completionType, String... operations) {
      String type = safe(completionType);
      if (type.isEmpty()) throw new IllegalArgumentException("completionType required");
      evidenceByType.put(
          type,
          operations == null
              ? Collections.emptyList()
              : new ArrayList<>(Arrays.asList(operations)));
      return this;
    }

    public Builder exemptCompletionTypes(String... completionTypes) {
      if (completionTypes != null) {
        for (String type : completionTypes) {
          String value = safe(type);
          if (!value.isEmpty()) evidenceExemptTypes.add(value);
        }
      }
      return this;
    }

    public Builder requireQualityReview(String... completionTypes) {
      if (completionTypes != null) {
        for (String type : completionTypes) {
          String value = safe(type);
          if (!value.isEmpty()) qualityReviewTypes.add(value);
        }
      }
      return this;
    }

    public Builder finalizeEvidenceRequired(boolean required) {
      finalizeEvidenceRequired = required;
      return this;
    }

    public CodeValidationContract build() {
      return new CodeValidationContract(this);
    }
  }
}
