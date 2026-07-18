package com.cscjapp.aiworkbench.sample;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.cscjapp.aiworkbench.android.AIWorkbench;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.api.ModelEndpoint;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import com.cscjapp.aiworkbench.tools.file.LocalWorkspaceAccess;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

final class PlaygroundRuntime {
  static final String DEFINITION_ID = "playground";
  static final String CODE_DEFINITION_ID = "playground-code-agent";
  static final String MODE_OFFLINE = "offline";
  static final String MODE_REAL = "real";
  static final String PROTOCOL_NATIVE = "native";
  static final String PROTOCOL_LEGACY = "legacy";
  static final String EXTRA_MODE = "playground_mode";
  static final String EXTRA_PROTOCOL = "playground_protocol";

  private static final String PREFS = "aiw_playground";
  private static volatile PlaygroundRuntime instance;

  private final Application application;
  private final SharedPreferences preferences;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final CopyOnWriteArrayList<Runnable> stateObservers = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Runnable> logObservers = new CopyOnWriteArrayList<>();
  private final ArrayDeque<String> eventLog = new ArrayDeque<>();
  private final Set<String> selectedContexts = new LinkedHashSet<>();
  private final LocalWorkspaceAccess workspace;
  private volatile String volatileApiKey;
  private volatile String latestArtifact = "";

  static PlaygroundRuntime get(Context context) {
    if (instance == null) {
      synchronized (PlaygroundRuntime.class) {
        if (instance == null) {
          instance = new PlaygroundRuntime((Application) context.getApplicationContext());
        }
      }
    }
    return instance;
  }

  private PlaygroundRuntime(Application application) {
    this.application = application;
    preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    volatileApiKey = BuildConfig.PLAYGROUND_API_KEY;
    try {
      File root = new File(application.getFilesDir(), "playground-workspace");
      if (!root.isDirectory() && !root.mkdirs()) {
        throw new IllegalStateException("无法创建 Playground 工作区");
      }
      seed(root, "README.md", "# AI Workbench Playground\n\n这是 SDK 的独立沙箱工作区。\n");
      seed(root, "demo.txt", "Playground 初始文本：等待 AI 修改。\n");
      seed(root, "code-agent-demo.txt", "Code Agent 待读取和修改的初始内容。\n");
      seed(
          root,
          "index.html",
          "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
              + "<title>Playground</title><h1>AI Workbench Playground</h1>");
      workspace = new LocalWorkspaceAccess("playground", root);
      selectedContexts.add("README.md");
      log("runtime", "Playground 工作区已就绪：" + root.getAbsolutePath());
    } catch (Exception error) {
      throw new IllegalStateException("初始化 Playground 工作区失败", error);
    }
  }

  Application application() {
    return application;
  }

  LocalWorkspaceAccess workspace() {
    return workspace;
  }

  ModelEndpoint endpoint(WorkbenchLaunchRequest request) {
    if (MODE_REAL.equals(mode(request))) {
      return new ModelEndpoint(
          baseUrl(),
          apiKey(),
          model(),
          temperature(),
          nativeTools(),
          deepThinking());
    }
    boolean nativeProtocol = !PROTOCOL_LEGACY.equals(protocol(request));
    return new ModelEndpoint(
        "offline://playground",
        "",
        nativeProtocol ? "scripted-native" : "scripted-legacy",
        0.2d,
        nativeProtocol,
        true);
  }

  String mode(WorkbenchLaunchRequest request) {
    if (request == null) return MODE_OFFLINE;
    String value = request.extras().get(EXTRA_MODE);
    return MODE_REAL.equals(value) ? MODE_REAL : MODE_OFFLINE;
  }

  String protocol(WorkbenchLaunchRequest request) {
    if (request == null) return PROTOCOL_NATIVE;
    String value = request.extras().get(EXTRA_PROTOCOL);
    return PROTOCOL_LEGACY.equals(value) ? PROTOCOL_LEGACY : PROTOCOL_NATIVE;
  }

  void openWorkbench(Context context, String mode, String demand, String protocol) {
    openWorkbench(context, DEFINITION_ID, mode, demand, protocol);
  }

  void openCodeAgent(Context context, String demand, String protocol) {
    openWorkbench(context, CODE_DEFINITION_ID, MODE_OFFLINE, demand, protocol);
  }

  private void openWorkbench(
      Context context, String definitionId, String mode, String demand, String protocol) {
    WorkbenchLaunchRequest request =
        WorkbenchLaunchRequest.builder(definitionId)
            .workspaceId("playground")
            .initialDemand(demand)
            .selectedArtifact(new File(workspace.rootDirectory(), "README.md").getAbsolutePath())
            .deepThinking(true)
            .extra(EXTRA_MODE, MODE_REAL.equals(mode) ? MODE_REAL : MODE_OFFLINE)
            .extra(
                EXTRA_PROTOCOL,
                PROTOCOL_LEGACY.equals(protocol) ? PROTOCOL_LEGACY : PROTOCOL_NATIVE)
            .build();
    log("launch", definitionId + " / " + mode(request) + " / " + protocol(request));
    AIWorkbench.open(context, request);
  }

  boolean hasRealConfiguration() {
    return realConfigurationError(baseUrl(), model(), String.valueOf(temperature())).isEmpty();
  }

  String realConfigurationSummary() {
    if (!hasRealConfiguration()) return "未配置";
    return model() + " · " + baseUrl();
  }

  String baseUrl() {
    return preferences.getString("base_url", BuildConfig.PLAYGROUND_BASE_URL);
  }

  String model() {
    return preferences.getString("model", BuildConfig.PLAYGROUND_MODEL);
  }

  double temperature() {
    try {
      return Double.parseDouble(preferences.getString("temperature", "0.2"));
    } catch (Exception ignored) {
      return 0.2d;
    }
  }

  boolean nativeTools() {
    return preferences.getBoolean("native_tools", true);
  }

  boolean deepThinking() {
    return preferences.getBoolean("deep_thinking", true);
  }

  String apiKey() {
    return volatileApiKey == null ? "" : volatileApiKey;
  }

  void saveRealConfiguration(
      String baseUrl,
      String apiKey,
      String model,
      String temperature,
      boolean nativeTools,
      boolean deepThinking) {
    preferences
        .edit()
        .putString("base_url", normalizeBaseUrl(baseUrl))
        .putString("model", safe(model).trim())
        .putString("temperature", normalizeTemperature(temperature))
        .putBoolean("native_tools", nativeTools)
        .putBoolean("deep_thinking", deepThinking)
        .apply();
    volatileApiKey = safe(apiKey).trim();
    log("settings", "真实模型配置已更新：" + realConfigurationSummary());
    notifyChanged();
  }

  static String realConfigurationError(String baseUrl, String model, String temperature) {
    String normalizedUrl = normalizeBaseUrl(baseUrl);
    if (normalizedUrl.isEmpty()) return "Base URL 不能为空";
    try {
      URI uri = URI.create(normalizedUrl);
      String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
      if (!("http".equals(scheme) || "https".equals(scheme)) || safe(uri.getHost()).isEmpty()) {
        return "Base URL 必须是有效的 HTTP/HTTPS 地址";
      }
    } catch (Exception ignored) {
      return "Base URL 格式无效";
    }
    if (safe(model).trim().isEmpty()) return "Model 不能为空";
    try {
      double value = Double.parseDouble(safe(temperature).trim());
      if (value < 0d || value > 2d) return "Temperature 必须在 0～2 之间";
    } catch (Exception ignored) {
      return "Temperature 必须是 0～2 之间的数字";
    }
    return "";
  }

  synchronized void toggleContext(String relativePath) {
    if (!selectedContexts.remove(relativePath)) selectedContexts.add(relativePath);
    log("context", (selectedContexts.contains(relativePath) ? "已添加 " : "已移除 ") + relativePath);
    notifyChanged();
  }

  synchronized void removeContext(String relativePath) {
    if (selectedContexts.remove(relativePath)) {
      log("context", "已移除 " + relativePath);
      notifyChanged();
    }
  }

  synchronized void clearContexts() {
    selectedContexts.clear();
    log("context", "已清空上下文");
    notifyChanged();
  }

  synchronized List<String> selectedContexts() {
    return new ArrayList<>(selectedContexts);
  }

  List<String> workspaceFiles() {
    try {
      List<String> values = workspace.list(".");
      List<String> files = new ArrayList<>();
      for (String value : values) if (!value.endsWith("/")) files.add(value);
      return files;
    } catch (Exception error) {
      log("workspace", "读取文件列表失败：" + error.getMessage());
      return new ArrayList<>();
    }
  }

  synchronized String nextGeneratedPath() {
    int index = 1;
    while (new File(workspace.rootDirectory(), "generated-" + index + ".txt").exists()) {
      index++;
    }
    return "generated-" + index + ".txt";
  }

  void setLatestArtifact(String path) {
    latestArtifact = safe(path);
    log("artifact", "最新产物：" + latestArtifact);
    notifyChanged();
  }

  String latestArtifact() {
    return latestArtifact;
  }

  void log(String category, String message) {
    String line =
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date())
            + "  ["
            + safe(category)
            + "] "
            + safe(message);
    synchronized (eventLog) {
      eventLog.addLast(line);
      while (eventLog.size() > 240) eventLog.removeFirst();
    }
    notifyObservers(logObservers);
  }

  String logs() {
    StringBuilder output = new StringBuilder();
    synchronized (eventLog) {
      for (String line : eventLog) {
        if (output.length() > 0) output.append('\n');
        output.append(line);
      }
    }
    return output.toString();
  }

  void clearLogs() {
    synchronized (eventLog) {
      eventLog.clear();
    }
    log("runtime", "事件日志已清空");
  }

  Cancellable observeState(Runnable observer) {
    if (observer == null) return Cancellable.NONE;
    stateObservers.add(observer);
    return () -> stateObservers.remove(observer);
  }

  Cancellable observeLogs(Runnable observer) {
    if (observer == null) return Cancellable.NONE;
    logObservers.add(observer);
    return () -> logObservers.remove(observer);
  }

  void runOnMain(Runnable action) {
    if (Looper.myLooper() == Looper.getMainLooper()) action.run();
    else mainHandler.post(action);
  }

  void notifyChanged() {
    notifyObservers(stateObservers);
  }

  private void notifyObservers(CopyOnWriteArrayList<Runnable> targets) {
    runOnMain(
        () -> {
          for (Runnable observer : targets) {
            try {
              observer.run();
            } catch (Throwable ignored) {
            }
          }
        });
  }

  Intent activityIntent(Class<?> type) {
    return new Intent(application, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  private static void seed(File root, String name, String content) throws Exception {
    File file = new File(root, name);
    if (file.exists()) return;
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String normalizeBaseUrl(String value) {
    String result = safe(value).trim();
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }

  private static String normalizeTemperature(String value) {
    try {
      double parsed = Double.parseDouble(safe(value).trim());
      return String.valueOf(Math.max(0d, Math.min(2d, parsed)));
    } catch (Exception ignored) {
      return "0.2";
    }
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
