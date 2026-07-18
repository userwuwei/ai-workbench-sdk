package com.cscjapp.aiworkbench.api;

public interface UserDecisionService {
  Cancellable request(UserDecisionRequest request, Callback callback);

  interface Callback {
    void onDecision(String optionId);

    void onCancelled();
  }
}
