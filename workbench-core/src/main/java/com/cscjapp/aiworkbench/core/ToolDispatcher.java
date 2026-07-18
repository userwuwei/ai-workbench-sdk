package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class ToolDispatcher {
  private final ToolRegistry registry;
  private final List<ToolPolicy> policies;
  private final List<AgentRunLifecycle> lifecyclePolicies;
  private final ToolContext context;

  public ToolDispatcher(ToolRegistry r, List<ToolPolicy> p, ToolContext c) {
    registry = r;
    policies = p == null ? Collections.emptyList() : new ArrayList<>(p);
    lifecyclePolicies = new ArrayList<>();
    for (ToolPolicy policy : policies) {
      if (policy instanceof AgentRunLifecycle) {
        lifecyclePolicies.add((AgentRunLifecycle) policy);
      }
    }
    context = c;
  }

  public Cancellable dispatch(String callId, String name, ToolArguments args, ToolCallback cb) {
    return dispatch(null, callId, name, args, cb);
  }

  public Cancellable dispatch(
      AgentRunContext runContext,
      String callId,
      String name,
      ToolArguments args,
      ToolCallback cb) {
    AgentTool tool = registry.find(name);
    if (tool == null) {
      cb.onComplete(ToolResult.error("unsupported_tool", "不支持的工具: " + name, false));
      return Cancellable.NONE;
    }
    Run run = new Run(runContext, callId, tool, cb);
    run.next(0, args);
    return run;
  }

  public void onRunStarted(AgentRunContext runContext) {
    if (runContext == null) return;
    for (AgentRunLifecycle lifecycle : lifecyclePolicies) {
      try {
        lifecycle.onRunStarted(runContext);
      } catch (Throwable ignored) {
      }
    }
  }

  public void onRunFinished(AgentRunContext runContext, String state) {
    if (runContext == null) return;
    for (AgentRunLifecycle lifecycle : lifecyclePolicies) {
      try {
        lifecycle.onRunFinished(runContext, state);
      } catch (Throwable ignored) {
      }
    }
  }

  private final class Run implements Cancellable {
    final String callId;
    final AgentTool tool;
    final ToolCallback cb;
    final AgentRunContext runContext;
    final AtomicBoolean done = new AtomicBoolean();
    volatile Cancellable active = Cancellable.NONE;
    volatile ToolArguments executingArguments = ToolArguments.empty();

    Run(AgentRunContext runContext, String id, AgentTool t, ToolCallback c) {
      this.runContext = runContext;
      callId = id;
      tool = t;
      cb = c;
    }

    void next(int i, ToolArguments args) {
      if (done.get()) return;
      if (i >= policies.size()) {
        execute(args);
        return;
      }
      ToolInvocation inv = new ToolInvocation(callId, tool, args);
      ToolPolicy p = policies.get(i);
      if (!p.supports(inv)) {
        next(i + 1, args);
        return;
      }
      active =
          p.evaluate(
              context,
              inv,
              d -> {
                if (d == null) {
                  complete(ToolResult.error("policy_error", "策略没有返回决策", false));
                  return;
                }
                if (d.kind() == ToolPolicyDecision.Kind.PROCEED)
                  next(i + 1, d.arguments() == null ? args : d.arguments());
                else complete(d.result());
              });
    }

    void execute(ToolArguments args) {
      executingArguments = args == null ? ToolArguments.empty() : args;
      active =
          tool.execute(
              context,
              executingArguments,
              new ToolCallback() {
                public void onProgress(String s, long c, long t, String m) {
                  if (!done.get()) cb.onProgress(s, c, t, m);
                }

                public void onComplete(ToolResult r) {
                  complete(r == null ? ToolResult.error("empty_result", "工具没有返回结果", false) : r);
                }
              });
    }

    void complete(ToolResult r) {
      if (!done.compareAndSet(false, true)) return;
      if (runContext != null) {
        ToolInvocation invocation =
            new ToolInvocation(callId, tool, executingArguments);
        for (AgentRunLifecycle lifecycle : lifecyclePolicies) {
          try {
            lifecycle.onToolCompleted(runContext, invocation, r);
          } catch (Throwable ignored) {
          }
        }
      }
      cb.onComplete(r);
    }

    public void cancel() {
      if (done.compareAndSet(false, true)) {
        active.cancel();
        cb.onComplete(ToolResult.cancelled("工具执行已取消"));
      }
    }
  }
}
