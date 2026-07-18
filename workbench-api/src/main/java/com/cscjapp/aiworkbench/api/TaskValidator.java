package com.cscjapp.aiworkbench.api;

public interface TaskValidator {
  Cancellable validate(ValidationContext context, Callback callback);

  interface Callback {
    void onComplete(ValidationResult result);
  }
}
