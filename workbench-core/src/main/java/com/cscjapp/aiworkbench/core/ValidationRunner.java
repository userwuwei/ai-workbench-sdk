package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ValidationRunner {
  private final List<TaskValidator> validators;

  public ValidationRunner(List<TaskValidator> v) {
    validators = v == null ? Collections.emptyList() : new ArrayList<>(v);
  }

  public Cancellable run(ValidationContext c, TaskValidator.Callback cb) {
    Run r = new Run(c, cb);
    r.next(0);
    return r;
  }

  private final class Run implements Cancellable {
    final ValidationContext c;
    final TaskValidator.Callback cb;
    final List<ValidationIssue> issues = new ArrayList<>();
    final AtomicBoolean done = new AtomicBoolean();
    volatile Cancellable active = Cancellable.NONE;

    Run(ValidationContext c, TaskValidator.Callback cb) {
      this.c = c;
      this.cb = cb;
    }

    void next(int i) {
      if (done.get()) return;
      if (i >= validators.size()) {
        if (done.compareAndSet(false, true)) cb.onComplete(new ValidationResult(issues));
        return;
      }
      active =
          validators
              .get(i)
              .validate(
                  c,
                  r -> {
                    if (r != null) issues.addAll(r.issues());
                    next(i + 1);
                  });
    }

    public void cancel() {
      if (done.compareAndSet(false, true)) active.cancel();
    }
  }
}
