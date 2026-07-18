package com.cscjapp.aiworkbench.sample;

import android.app.Application;
import com.cscjapp.aiworkbench.android.AIWorkbench;
import com.cscjapp.aiworkbench.android.WorkbenchRuntimeOptions;
import com.cscjapp.aiworkbench.api.AccessPolicy;
import com.cscjapp.aiworkbench.api.ThemeConfig;
import com.cscjapp.aiworkbench.api.WorkbenchSdkConfig;

public final class SampleApp extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    PlaygroundRuntime runtime = PlaygroundRuntime.get(this);
    AIWorkbench.install(
        this,
        WorkbenchSdkConfig.builder()
            .registerFactory(
                PlaygroundRuntime.DEFINITION_ID,
                request -> new PlaygroundDefinition(runtime, request, false))
            .registerFactory(
                PlaygroundRuntime.CODE_DEFINITION_ID,
                request -> new PlaygroundDefinition(runtime, request, true))
            .modelConfigProvider(runtime::endpoint)
            .accessPolicy(
                (action, request, callback) -> authorize(runtime, action, request, callback))
            .themeConfig(
                new ThemeConfig(
                    "AI Workbench Playground",
                    "离线模式不会访问网络；真实模式使用当前 Playground 配置",
                    0xff38bdf8))
            .build(),
        WorkbenchRuntimeOptions.builder()
            .modelGatewayFactory(new PlaygroundGatewayFactory(runtime))
            .build());
    runtime.log("install", "AIWorkbench.install 完成");
  }

  private static void authorize(
      PlaygroundRuntime runtime,
      String action,
      com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest request,
      AccessPolicy.Callback callback) {
    if (PlaygroundRuntime.MODE_REAL.equals(runtime.mode(request))
        && ("open_workbench".equals(action) || "submit_demand".equals(action))
        && !runtime.hasRealConfiguration()) {
      callback.deny("请先配置真实模型的 Base URL 与 Model", "select_model");
      return;
    }
    callback.allow();
  }
}
