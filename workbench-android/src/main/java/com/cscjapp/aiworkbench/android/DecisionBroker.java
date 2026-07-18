package com.cscjapp.aiworkbench.android;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.cscjapp.aiworkbench.api.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class DecisionBroker implements UserDecisionService {
  static final class Pending {
    final UserDecisionRequest request;
    final Callback callback;
    final AtomicBoolean resolved = new AtomicBoolean();

    Pending(UserDecisionRequest r, Callback c) {
      request = r;
      callback = c;
    }
  }

  private final MutableLiveData<Pending> pending = new MutableLiveData<>();

  LiveData<Pending> pending() {
    return pending;
  }

  public Cancellable request(UserDecisionRequest r, Callback c) {
    Pending p = new Pending(r, c);
    pending.postValue(p);
    return () -> {
      if (p.resolved.compareAndSet(false, true)) {
        c.onCancelled();
        pending.postValue(null);
      }
    };
  }

  void decide(Pending p, String id) {
    if (p != null && p.resolved.compareAndSet(false, true)) {
      p.callback.onDecision(id);
      pending.setValue(null);
    }
  }

  void cancel(Pending p) {
    if (p != null && p.resolved.compareAndSet(false, true)) {
      p.callback.onCancelled();
      pending.setValue(null);
    }
  }
}
