package com.cscjapp.aiworkbench.api;

public interface Cancellable {
  Cancellable NONE = () -> {};

  void cancel();
}
