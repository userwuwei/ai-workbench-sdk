package com.cscjapp.aiworkbench.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import com.cscjapp.aiworkbench.api.*;
import com.kongzue.dialogx.DialogX;

public final class AIWorkbench {
  private static volatile WorkbenchSdkConfig config;
  private static volatile WorkbenchRuntimeOptions runtimeOptions = WorkbenchRuntimeOptions.defaults();

  private AIWorkbench() {}

  public static synchronized void install(Application application, WorkbenchSdkConfig sdkConfig) {
    install(application, sdkConfig, WorkbenchRuntimeOptions.defaults());
  }

  public static synchronized void install(
      Application application,
      WorkbenchSdkConfig sdkConfig,
      WorkbenchRuntimeOptions options) {
    if (application == null || sdkConfig == null)
      throw new IllegalArgumentException("application/config required");
    if (options == null) throw new IllegalArgumentException("runtime options required");
    config = sdkConfig;
    runtimeOptions = options;
    DialogX.init(application);
  }

  public static boolean isInstalled() {
    return config != null;
  }

  static WorkbenchSdkConfig config() {
    if (config == null) throw new IllegalStateException("AIWorkbench.install must be called first");
    return config;
  }

  static WorkbenchRuntimeOptions runtimeOptions() {
    if (config == null) throw new IllegalStateException("AIWorkbench.install must be called first");
    return runtimeOptions;
  }

  public static void open(Activity activity, WorkbenchLaunchRequest request) {
    open((Context) activity, request);
  }

  public static void open(Context context, WorkbenchLaunchRequest request) {
    if (context == null || request == null)
      throw new IllegalArgumentException("context/request required");
    if (!isInstalled()) {
      throw new IllegalStateException(
          "AIWorkbench.install() must be called from Application.onCreate() before open()");
    }
    Intent intent = new Intent(context, AIWorkbenchActivity.class);
    AIWorkbenchActivity.putRequest(intent, request);
    if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }
}
