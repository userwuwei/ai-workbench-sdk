package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.ContextProvider;
import com.cscjapp.aiworkbench.api.PromptContext;
import com.cscjapp.aiworkbench.api.PromptContributor;
import com.cscjapp.aiworkbench.api.PromptPhase;
import com.cscjapp.aiworkbench.api.PromptSection;
import com.cscjapp.aiworkbench.api.WorkbenchDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PromptComposer {
  public String compose(WorkbenchDefinition definition, PromptContext context) {
    List<PromptSection> all = new ArrayList<>();
    for (PromptContributor contributor : safe(definition.promptContributors())) {
      List<PromptSection> sections = contributor.contribute(context);
      if (sections != null) all.addAll(sections);
    }
    for (ContextProvider provider : safe(definition.contextProviders())) {
      try {
        List<PromptSection> sections = provider.provide(context);
        if (sections != null) all.addAll(sections);
      } catch (Exception error) {
        all.add(
            new PromptSection(
                "context_error",
                PromptPhase.CONTEXT,
                Integer.MAX_VALUE,
                1200,
                "上下文读取失败：" + error.getMessage()));
      }
    }
    all.sort(
        Comparator.comparing(PromptSection::phase)
            .thenComparingInt(PromptSection::priority)
            .thenComparing(PromptSection::id));
    StringBuilder out = new StringBuilder();
    Set<String> ids = new HashSet<>();
    for (PromptSection section : all) {
      if (section == null || !ids.add(section.id())) continue;
      String value = section.content();
      if (section.budgetChars() > 0 && value.length() > section.budgetChars()) {
        value = value.substring(0, section.budgetChars()) + "\n...[truncated]";
      }
      if (value.trim().isEmpty()) continue;
      out.append("## ").append(section.id()).append('\n').append(value.trim()).append("\n\n");
    }
    return out.toString().trim();
  }

  private static <T> List<T> safe(List<T> value) {
    return value == null ? Collections.emptyList() : value;
  }
}
