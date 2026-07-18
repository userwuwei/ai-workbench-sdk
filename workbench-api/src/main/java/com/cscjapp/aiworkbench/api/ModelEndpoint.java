package com.cscjapp.aiworkbench.api;

import java.util.Collections;

public final class ModelEndpoint {
  private final String baseUrl, apiKey, modelId;
  private final double temperature;
  private final boolean nativeTools, deepThinking;
  private final ModelHeaderProvider headerProvider;

  public ModelEndpoint(
      String baseUrl,
      String apiKey,
      String modelId,
      double temperature,
      boolean nativeTools,
      boolean deepThinking) {
    this(
        baseUrl,
        apiKey,
        modelId,
        temperature,
        nativeTools,
        deepThinking,
        endpoint -> Collections.emptyMap());
  }

  public ModelEndpoint(
      String baseUrl,
      String apiKey,
      String modelId,
      double temperature,
      boolean nativeTools,
      boolean deepThinking,
      ModelHeaderProvider headerProvider) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.modelId = modelId;
    this.temperature = temperature;
    this.nativeTools = nativeTools;
    this.deepThinking = deepThinking;
    this.headerProvider =
        headerProvider == null ? endpoint -> Collections.emptyMap() : headerProvider;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public String apiKey() {
    return apiKey;
  }

  public String modelId() {
    return modelId;
  }

  public double temperature() {
    return temperature;
  }

  public boolean nativeTools() {
    return nativeTools;
  }

  public boolean deepThinking() {
    return deepThinking;
  }

  public ModelHeaderProvider headerProvider() {
    return headerProvider;
  }
}
