package com.cscjapp.aiworkbench.sample;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.cscjapp.aiworkbench.sample.databinding.ActivityArtifactPreviewBinding;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class ArtifactPreviewActivity extends AppCompatActivity {
  static final String EXTRA_PATH = "playground_artifact_path";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    ActivityArtifactPreviewBinding binding =
        ActivityArtifactPreviewBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    binding.buttonBack.setOnClickListener(ignored -> finish());
    String path = getIntent().getStringExtra(EXTRA_PATH);
    binding.textPath.setText(path == null ? "未知路径" : path);
    binding.textContent.setText(read(path));
  }

  private String read(String path) {
    try {
      File file = PlaygroundRuntime.get(this).workspace().resolveSafely(path);
      if (!file.isFile()) return "目标不是文件";
      if (file.length() > 1_000_000L) return "文件超过 1MB，Playground 预览已阻止加载";
      byte[] data = new byte[(int) file.length()];
      try (FileInputStream input = new FileInputStream(file)) {
        int offset = 0;
        while (offset < data.length) {
          int read = input.read(data, offset, data.length - offset);
          if (read < 0) break;
          offset += read;
        }
      }
      return new String(data, StandardCharsets.UTF_8);
    } catch (Exception error) {
      return "读取失败：" + error.getMessage();
    }
  }
}
