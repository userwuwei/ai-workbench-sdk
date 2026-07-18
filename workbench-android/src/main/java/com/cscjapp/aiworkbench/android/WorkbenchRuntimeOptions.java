package com.cscjapp.aiworkbench.android;

import com.cscjapp.aiworkbench.core.ModelGatewayFactory;
import com.cscjapp.aiworkbench.model.openai.OpenAIModelGateway;

/** Optional process-wide runtime hooks. App behavior remains configured by WorkbenchSdkConfig. */
public final class WorkbenchRuntimeOptions {
  private final ModelGatewayFactory modelGatewayFactory;

  private WorkbenchRuntimeOptions(Builder builder) {
    modelGatewayFactory = builder.modelGatewayFactory;
    if (modelGatewayFactory == null) {
      throw new IllegalStateException("modelGatewayFactory required");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static WorkbenchRuntimeOptions defaults() {
    return builder().modelGatewayFactory((request, endpoint) -> new OpenAIModelGateway()).build();
  }

  public ModelGatewayFactory modelGatewayFactory() {
    return modelGatewayFactory;
  }

  public static final class Builder {
    private ModelGatewayFactory modelGatewayFactory =
        (request, endpoint) -> new OpenAIModelGateway();

    public Builder modelGatewayFactory(ModelGatewayFactory value) {
      modelGatewayFactory = value;
      return this;
    }

    public WorkbenchRuntimeOptions build() {
      return new WorkbenchRuntimeOptions(this);
    }
  }
}
