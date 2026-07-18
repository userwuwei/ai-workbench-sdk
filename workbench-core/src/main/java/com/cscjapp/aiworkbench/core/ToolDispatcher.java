package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class ToolDispatcher {
  private final ToolRegistry registry;
  private final List<ToolPolicy> policies;
  private final ToolContext context;

  public ToolDispatcher(ToolRegistry r, List<ToolPolicy> p, ToolContext c) {
    registry = r;
    policies = p == null ? Collections.emptyList() : new ArrayList<>(p);
    context = c;
  }

  public Cancellable dispatch(String callId, String name, ToolArguments args, ToolCallback cb) {
    AgentTool tool = registry.find(name);
    if (tool == null) {
      cb.onComplete(ToolResult.error("unsupported_tool", "不支持的工具: " + name, false));
      return Cancellable.NONE;
    }
    Run run = new Run(callId, tool, cb);
    run.next(0, args);
    return run;
  }

  private final class Run implements Cancellable {
    final String callId;
    final AgentTool tool;
    final ToolCallback cb;
    final AtomicBoolean done = new AtomicBoolean();
    volatile Cancellable active = Cancellable.NONE;

    Run(String id, AgentTool t, ToolCallback c) {
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
      active =
          tool.execute(
              context,
              args,
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
      if (done.compareAndSet(false, true)) cb.onComplete(r);
    }

    public void cancel() {
      if (done.compareAndSet(false, true)) {
        active.cancel();
        cb.onComplete(ToolResult.cancelled("工具执行已取消"));
      }
    }
  }
}
