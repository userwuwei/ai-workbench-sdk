package com.cscjapp.aiworkbench.core;

import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;

/** Creates the model transport used by one workbench Activity/session. */
public interface ModelGatewayFactory {
  ModelGateway create(WorkbenchLaunchRequest request, ModelEndpoint endpoint);
}
