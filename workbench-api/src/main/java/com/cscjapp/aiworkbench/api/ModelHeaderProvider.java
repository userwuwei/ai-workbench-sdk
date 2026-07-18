package com.cscjapp.aiworkbench.api;

import java.util.Map;

public interface ModelHeaderProvider {
  Map<String, String> headers(ModelEndpoint endpoint);
}
