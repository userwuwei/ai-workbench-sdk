package com.cscjapp.aiworkbench.sample;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.cscjapp.aiworkbench.sample.databinding.ActivityModelSettingsBinding;

public final class ModelSettingsActivity extends AppCompatActivity {
  private ActivityModelSettingsBinding binding;
  private PlaygroundRuntime runtime;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityModelSettingsBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    runtime = PlaygroundRuntime.get(this);

    binding.inputBaseUrl.setText(runtime.baseUrl());
    binding.inputModel.setText(runtime.model());
    binding.inputApiKey.setText(runtime.apiKey());
    binding.inputApiKey.setInputType(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    binding.inputTemperature.setText(String.valueOf(runtime.temperature()));
    binding.switchNativeTools.setChecked(runtime.nativeTools());
    binding.switchDeepThinking.setChecked(runtime.deepThinking());
    binding.buttonBack.setOnClickListener(ignored -> finish());
    binding.buttonSave.setOnClickListener(ignored -> save(false));
    binding.buttonSaveAndOpen.setOnClickListener(ignored -> save(true));
  }

  private void save(boolean open) {
    String baseUrl = text(binding.inputBaseUrl);
    String model = text(binding.inputModel);
    String temperature = text(binding.inputTemperature);
    String validation =
        PlaygroundRuntime.realConfigurationError(baseUrl, model, temperature);
    if (!validation.isEmpty()) {
      Toast.makeText(this, validation, Toast.LENGTH_LONG).show();
      return;
    }
    runtime.saveRealConfiguration(
        baseUrl,
        text(binding.inputApiKey),
        model,
        temperature,
        binding.switchNativeTools.isChecked(),
        binding.switchDeepThinking.isChecked());
    Toast.makeText(this, "真实模型配置已保存", Toast.LENGTH_SHORT).show();
    if (open) {
      runtime.openWorkbench(
          this,
          PlaygroundRuntime.MODE_REAL,
          "",
          binding.switchNativeTools.isChecked()
              ? PlaygroundRuntime.PROTOCOL_NATIVE
              : PlaygroundRuntime.PROTOCOL_LEGACY);
    } else {
      finish();
    }
  }

  private static String text(android.widget.EditText view) {
    return view.getText() == null ? "" : view.getText().toString();
  }
}
