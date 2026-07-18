package com.cscjapp.aiworkbench.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import com.cscjapp.aiworkbench.api.*;
import com.kongzue.dialogx.DialogX;

public final class AIWorkbench {
  private static volatile WorkbenchSdkConfig config;

  private AIWorkbench() {}

  public static synchronized void install(Application application, WorkbenchSdkConfig sdkConfig) {
    if (application == null || sdkConfig == null)
      throw new IllegalArgumentException("application/config required");
    config = sdkConfig;
    DialogX.init(application);
  }

  public static boolean isInstalled() {
    return config != null;
  }

  static WorkbenchSdkConfig config() {
    if (config == null) throw new IllegalStateException("AIWorkbench.install must be called first");
    return config;
  }

  public static void open(Activity activity, WorkbenchLaunchRequest request) {
    open((Context) activity, request);
  }

  public static void open(Context context, WorkbenchLaunchRequest request) {
    if (context == null || request == null)
      throw new IllegalArgumentException("context/request required");
    Intent intent = new Intent(context, AIWorkbenchActivity.class);
    AIWorkbenchActivity.putRequest(intent, request);
    if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }
}
