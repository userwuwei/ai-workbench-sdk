package com.cscjapp.aiworkbench.api;

import java.util.List;

public interface ContextProvider {
  List<PromptSection> provide(PromptContext context) throws Exception;
}
