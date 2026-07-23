package com.cscjapp.aiworkbench.api;

import java.util.List;

public interface ToolPolicy {
  /**
   * Selects the tools visible to the model for one round.
   *
   * <p>Policies are applied in registration order. Each policy receives the tools retained by the
   * previous policy, so a policy can narrow the surface but cannot re-enable a tool removed by an
   * earlier policy. Invocation policies that do not participate in round routing keep the default
   * pass-through behavior.
   */
  default ToolSelection selectTools(
      AgentRoundContext context, List<ToolSpec> registeredTools) {
    return ToolSelection.all(registeredTools);
  }

  boolean supports(ToolInvocation invocation);

  Cancellable evaluate(ToolContext context, ToolInvocation invocation, ToolPolicyCallback callback);
}
