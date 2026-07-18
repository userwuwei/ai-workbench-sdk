package com.cscjapp.aiworkbench.sample;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.cscjapp.aiworkbench.sample.databinding.ActivityScenarioLabBinding;

public final class ScenarioLabActivity extends AppCompatActivity {
  private ActivityScenarioLabBinding binding;
  private PlaygroundRuntime runtime;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityScenarioLabBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    runtime = PlaygroundRuntime.get(this);
    binding.buttonBack.setOnClickListener(ignored -> finish());
    binding.buttonStream.setOnClickListener(
        ignored -> open("请演示 reasoning、流式输出和 echo 工具", PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonCreate.setOnClickListener(
        ignored -> open("请创建文件并写入离线测试内容", PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonLegacy.setOnClickListener(
        ignored -> open("请使用 legacy 协议读取 README.md", PlaygroundRuntime.PROTOCOL_LEGACY));
    binding.buttonConflict.setOnClickListener(
        ignored -> open("请触发 demo.txt 文件冲突覆盖场景", PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonLongText.setOnClickListener(
        ignored -> open("请生成 20000 字长文本性能场景", PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonLongArguments.setOnClickListener(
        ignored -> open("请运行 100000 字长参数工具场景", PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonError.setOnClickListener(
        ignored -> open("请模拟错误", PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonTimeout.setOnClickListener(
        ignored -> open("请模拟超时，等待期间允许取消", PlaygroundRuntime.PROTOCOL_NATIVE));
    binding.buttonHistory.setOnClickListener(
        ignored ->
            runtime.openWorkbench(
                this,
                PlaygroundRuntime.MODE_OFFLINE,
                "",
                PlaygroundRuntime.PROTOCOL_NATIVE));
  }

  private void open(String demand, String protocol) {
    runtime.openWorkbench(this, PlaygroundRuntime.MODE_OFFLINE, demand, protocol);
  }
}
