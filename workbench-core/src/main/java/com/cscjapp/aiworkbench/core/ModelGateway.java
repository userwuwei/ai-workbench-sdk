package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.Cancellable;

public interface ModelGateway {
  Cancellable stream(ModelRequest request, ModelStreamObserver observer);
}
