package com.cscjapp.aiworkbench.api;

public interface ToolPolicy {
  boolean supports(ToolInvocation invocation);

  Cancellable evaluate(ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback);
}
