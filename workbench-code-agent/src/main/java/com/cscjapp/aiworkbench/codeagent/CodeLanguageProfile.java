package com.cscjapp.aiworkbench.codeagent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Language-specific rules injected into the otherwise language-neutral Code Agent preset. */
public final class CodeLanguageProfile {
  private final String id;
  private final String languageRules;
  private final CodeValidationContract verificationContract;
  private final Map<String, Map<String, Object>> metaToolExtensions;

  private CodeLanguageProfile(Builder builder) {
    id = required(builder.id, "profile id");
    languageRules = safe(builder.languageRules);
    verificationContract =
        builder.verificationContract == null
            ? CodeValidationContract.builder().build()
            : builder.verificationContract;
    Map<String, Map<String, Object>> copied = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry :
        builder.metaToolExtensions.entrySet()) {
      copied.put(entry.getKey(), SchemaMaps.copyMap(entry.getValue()));
    }
    metaToolExtensions = Collections.unmodifiableMap(copied);
  }

  public static Builder builder(String id) {
    return new Builder(id);
  }

  public String id() {
    return id;
  }

  public String languageRules() {
    return languageRules;
  }

  public CodeValidationContract verificationContract() {
    return verificationContract;
  }

  public Map<String, Map<String, Object>> metaToolExtensions() {
    return metaToolExtensions;
  }

  private static String required(String value, String name) {
    String result = safe(value);
    if (result.isEmpty()) throw new IllegalArgumentException(name + " required");
    return result;
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }

  public static final class Builder {
    private final String id;
    private String languageRules = "";
    private CodeValidationContract verificationContract;
    private final Map<String, Map<String, Object>> metaToolExtensions =
        new LinkedHashMap<>();

    private Builder(String id) {
      this.id = id;
    }

    public Builder languageRules(String value) {
      languageRules = value;
      return this;
    }

    public Builder verificationContract(CodeValidationContract value) {
      verificationContract = value;
      return this;
    }

    public Builder metaToolExtensions(
        Map<String, ? extends Map<String, Object>> extensions) {
      metaToolExtensions.clear();
      if (extensions != null) {
        for (Map.Entry<String, ? extends Map<String, Object>> entry :
            extensions.entrySet()) {
          String name = safe(entry.getKey());
          if (!name.isEmpty() && entry.getValue() != null) {
            metaToolExtensions.put(name, SchemaMaps.copyMap(entry.getValue()));
          }
        }
      }
      return this;
    }

    public CodeLanguageProfile build() {
      return new CodeLanguageProfile(this);
    }
  }
}
