package com.cscjapp.aiworkbench.android;

import com.cscjapp.aiworkbench.core.ModelGatewayFactory;
import com.cscjapp.aiworkbench.core.WorkbenchLogger;
import com.cscjapp.aiworkbench.model.openai.OpenAIModelGateway;

/** Optional process-wide runtime hooks. App behavior remains configured by WorkbenchSdkConfig. */
public final class WorkbenchRuntimeOptions {
  private final ModelGatewayFactory modelGatewayFactory;
  private final WorkbenchLogger logger;

  private WorkbenchRuntimeOptions(Builder builder) {
    logger = builder.logger == null ? WorkbenchLogger.none() : builder.logger;
    modelGatewayFactory =
        builder.modelGatewayFactory == null
            ? (request, endpoint) -> new OpenAIModelGateway(logger)
            : builder.modelGatewayFactory;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static WorkbenchRuntimeOptions defaults() {
    return builder().build();
  }

  public ModelGatewayFactory modelGatewayFactory() {
    return modelGatewayFactory;
  }

  public WorkbenchLogger logger() {
    return logger;
  }

  public static final class Builder {
    private ModelGatewayFactory modelGatewayFactory;
    private WorkbenchLogger logger = WorkbenchLogger.none();

    public Builder modelGatewayFactory(ModelGatewayFactory value) {
      modelGatewayFactory = value;
      return this;
    }

    public Builder logger(WorkbenchLogger value) {
      logger = value;
      return this;
    }

    public WorkbenchRuntimeOptions build() {
      return new WorkbenchRuntimeOptions(this);
    }
  }
}
