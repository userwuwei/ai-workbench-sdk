package com.cscjapp.aiworkbench.sample;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.cscjapp.aiworkbench.android.AIWorkbench;
import com.cscjapp.aiworkbench.api.Cancellable;
import com.cscjapp.aiworkbench.sample.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {
  private ActivityMainBinding binding;
  private PlaygroundRuntime runtime;
  private Cancellable stateObservation = Cancellable.NONE;
  private Cancellable logObservation = Cancellable.NONE;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    runtime = PlaygroundRuntime.get(this);

    binding.buttonOffline.setOnClickListener(
        ignored ->
            runtime.openWorkbench(
                this,
                PlaygroundRuntime.MODE_OFFLINE,
                "",
                PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonCodeAgent.setOnClickListener(
        ignored ->
            runtime.openCodeAgent(
                this,
                "请完成一次规划、读取、修改、验证、质量检查和终态提交的 Code Agent 闭环",
                PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonReal.setOnClickListener(
        ignored -> {
          if (!runtime.hasRealConfiguration()) {
            startActivity(new Intent(this, ModelSettingsActivity.class));
          } else {
            runtime.openWorkbench(
                this,
                PlaygroundRuntime.MODE_REAL,
                "",
                runtime.nativeTools()
                    ? PlaygroundRuntime.PROTOCOL_NATIVE
                    : PlaygroundRuntime.PROTOCOL_LEGACY);
          }
        });
    binding.buttonScenarios.setOnClickListener(
        ignored -> startActivity(new Intent(this, ScenarioLabActivity.class)));
    binding.buttonSettings.setOnClickListener(
        ignored -> startActivity(new Intent(this, ModelSettingsActivity.class)));
    binding.buttonClearLogs.setOnClickListener(ignored -> runtime.clearLogs());
  }

  @Override
  protected void onStart() {
    super.onStart();
    stateObservation.cancel();
    logObservation.cancel();
    stateObservation = runtime.observeState(this::render);
    logObservation = runtime.observeLogs(this::render);
    render();
  }

  @Override
  protected void onStop() {
    stateObservation.cancel();
    logObservation.cancel();
    stateObservation = Cancellable.NONE;
    logObservation = Cancellable.NONE;
    super.onStop();
  }

  private void render() {
    binding.textInstallStatus.setText(
        AIWorkbench.isInstalled() ? "SDK 状态：已安装，可独立运行" : "SDK 状态：未安装");
    binding.textModeStatus.setText("默认模式：离线 ScriptedModelGateway");
    binding.textModelStatus.setText("真实模型：" + runtime.realConfigurationSummary());
    binding.textWorkspace.setText("沙箱：" + runtime.workspace().rootDirectory().getAbsolutePath());
    String logs = runtime.logs();
    binding.textLogs.setText(logs.isEmpty() ? "暂无事件" : logs);
    binding.scrollLogs.post(() -> binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN));
  }
}
