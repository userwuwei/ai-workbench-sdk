package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.core.ModelGateway;
import com.cscjapp.aiworkbench.core.WorkbenchLogger;
import com.cscjapp.aiworkbench.model.openai.OpenAIModelGateway;
import org.junit.Test;

public final class WorkbenchRuntimeOptionsTest {
  @Test
  public void defaultsToOpenAiGateway() {
    ModelGateway gateway =
        WorkbenchRuntimeOptions.defaults()
            .modelGatewayFactory()
            .create(request(), endpoint());
    assertTrue(gateway instanceof OpenAIModelGateway);
    assertNotNull(WorkbenchRuntimeOptions.defaults().logger());
  }

  @Test
  public void usesInjectedGatewayFactory() {
    ModelGateway marker = (request, observer) -> Cancellable.NONE;
    WorkbenchRuntimeOptions options =
        WorkbenchRuntimeOptions.builder()
            .modelGatewayFactory((request, endpoint) -> marker)
            .build();
    assertSame(marker, options.modelGatewayFactory().create(request(), endpoint()));
  }

  @Test
  public void keepsInjectedLogger() {
    WorkbenchLogger logger = (event, message) -> {};
    WorkbenchRuntimeOptions options =
        WorkbenchRuntimeOptions.builder().logger(logger).build();

    assertSame(logger, options.logger());
    assertTrue(
        options.modelGatewayFactory().create(request(), endpoint())
            instanceof OpenAIModelGateway);
  }

  private static WorkbenchLaunchRequest request() {
    return WorkbenchLaunchRequest.builder("test").workspaceId("workspace").build();
  }

  private static ModelEndpoint endpoint() {
    return new ModelEndpoint("https://example.test/v1", "", "test", 0.2, true, false);
  }
}
