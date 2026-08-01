package com.cscjapp.aiworkbench.api;

import java.util.Collections;

public final class ModelEndpoint {
  private final String baseUrl, apiKey, modelId;
  private final double temperature;
  private final boolean nativeTools, deepThinking, namedToolChoiceSupported;
  private final ModelHeaderProvider headerProvider;
  private final ToolArgumentMode toolArgumentMode;

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
        endpoint -> Collections.emptyMap(),
        ToolArgumentMode.BEST_EFFORT);
  }

  public ModelEndpoint(
      String baseUrl,
      String apiKey,
      String modelId,
      double temperature,
      boolean nativeTools,
      boolean deepThinking,
      ModelHeaderProvider headerProvider) {
    this(
        baseUrl,
        apiKey,
        modelId,
        temperature,
        nativeTools,
        deepThinking,
        headerProvider,
        ToolArgumentMode.BEST_EFFORT);
  }

  public ModelEndpoint(
      String baseUrl,
      String apiKey,
      String modelId,
      double temperature,
      boolean nativeTools,
      boolean deepThinking,
      ToolArgumentMode toolArgumentMode) {
    this(
        baseUrl,
        apiKey,
        modelId,
        temperature,
        nativeTools,
        deepThinking,
        endpoint -> Collections.emptyMap(),
        toolArgumentMode);
  }

  public ModelEndpoint(
      String baseUrl,
      String apiKey,
      String modelId,
      double temperature,
      boolean nativeTools,
      boolean deepThinking,
      ModelHeaderProvider headerProvider,
      ToolArgumentMode toolArgumentMode) {
    this(
        baseUrl,
        apiKey,
        modelId,
        temperature,
        nativeTools,
        deepThinking,
        true,
        headerProvider,
        toolArgumentMode);
  }

  public ModelEndpoint(
      String baseUrl,
      String apiKey,
      String modelId,
      double temperature,
      boolean nativeTools,
      boolean deepThinking,
      boolean namedToolChoiceSupported,
      ModelHeaderProvider headerProvider,
      ToolArgumentMode toolArgumentMode) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.modelId = modelId;
    this.temperature = temperature;
    this.nativeTools = nativeTools;
    this.deepThinking = deepThinking;
    this.namedToolChoiceSupported = namedToolChoiceSupported;
    this.headerProvider =
        headerProvider == null ? endpoint -> Collections.emptyMap() : headerProvider;
    this.toolArgumentMode =
        toolArgumentMode == null ? ToolArgumentMode.BEST_EFFORT : toolArgumentMode;
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

  public boolean namedToolChoiceSupported() {
    return namedToolChoiceSupported;
  }

  public ModelHeaderProvider headerProvider() {
    return headerProvider;
  }

  public ToolArgumentMode toolArgumentMode() {
    return toolArgumentMode;
  }
}
