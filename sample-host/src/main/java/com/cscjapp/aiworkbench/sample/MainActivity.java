package com.cscjapp.aiworkbench.sample;

import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.cscjapp.aiworkbench.android.AIWorkbench;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;

public final class MainActivity extends AppCompatActivity {
  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    Button v = new Button(this);
    v.setText("打开通用 AI 工作台");
    v.setOnClickListener(
        x ->
            AIWorkbench.open(
                this, WorkbenchLaunchRequest.builder("sample").workspaceId("sample").build()));
    setContentView(v);
  }
}
