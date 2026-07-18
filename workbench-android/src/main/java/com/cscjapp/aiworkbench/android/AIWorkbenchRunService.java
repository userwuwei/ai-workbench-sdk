package com.cscjapp.aiworkbench.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.Nullable;

/** Keeps the reference workbench run state visible while the host app is backgrounded. */
public final class AIWorkbenchRunService extends Service {
  private static final String CHANNEL_ID = "ai_workbench_run";
  private static final int NOTIFICATION_ID = 2407;
  private static final String ACTION_START = "com.cscjapp.aiworkbench.START";
  private static final String ACTION_UPDATE = "com.cscjapp.aiworkbench.UPDATE";
  private static final String EXTRA_STATUS = "status";
  private static final String EXTRA_LAUNCH = "launch_extras";

  private String currentStatus = "AI 正在处理项目";
  private Bundle launchExtras;

  static void start(Context context, String status, Intent workbenchIntent) {
    if (context == null) return;
    Intent intent = new Intent(context, AIWorkbenchRunService.class);
    intent.setAction(ACTION_START);
    intent.putExtra(EXTRA_STATUS, normalizeStatus(status));
    if (workbenchIntent != null && workbenchIntent.getExtras() != null) {
      intent.putExtra(EXTRA_LAUNCH, new Bundle(workbenchIntent.getExtras()));
    }
    startCompat(context, intent);
  }

  static void update(Context context, String status) {
    if (context == null) return;
    Intent intent = new Intent(context, AIWorkbenchRunService.class);
    intent.setAction(ACTION_UPDATE);
    intent.putExtra(EXTRA_STATUS, normalizeStatus(status));
    startCompat(context, intent);
  }

  static void stop(Context context) {
    if (context != null) context.stopService(new Intent(context, AIWorkbenchRunService.class));
  }

  private static void startCompat(Context context, Intent intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
    else context.startService(intent);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    ensureNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null) {
      String status = intent.getStringExtra(EXTRA_STATUS);
      if (status != null && !status.trim().isEmpty()) currentStatus = status.trim();
      Bundle nextExtras = intent.getBundleExtra(EXTRA_LAUNCH);
      if (nextExtras != null) launchExtras = new Bundle(nextExtras);
    }
    startForeground(NOTIFICATION_ID, buildNotification(currentStatus));
    return START_NOT_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    stopForeground(true);
    super.onDestroy();
  }

  private Notification buildNotification(String status) {
    Intent launch = new Intent(this, AIWorkbenchActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (launchExtras != null) launch.putExtras(new Bundle(launchExtras));
    PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
    return builder.setSmallIcon(R.drawable.aiw_ic_notification)
        .setContentTitle("AI 工作台运行中")
        .setContentText(status)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(pending)
        .build();
  }

  private void ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
    NotificationChannel channel = new NotificationChannel(
        CHANNEL_ID, "AI 工作台", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("AI 工作台后台运行状态");
    manager.createNotificationChannel(channel);
  }

  private static String normalizeStatus(String status) {
    return status == null || status.trim().isEmpty() ? "AI 正在处理项目" : status.trim();
  }
}
