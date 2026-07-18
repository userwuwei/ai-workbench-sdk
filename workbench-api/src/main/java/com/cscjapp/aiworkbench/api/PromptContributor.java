package com.cscjapp.aiworkbench.api;

import java.util.List;

public interface PromptContributor {
  List<PromptSection> contribute(PromptContext context);
}
