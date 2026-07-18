package com.cscjapp.aiworkbench.sample;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.cscjapp.aiworkbench.android.AIWorkbench;
import com.cscjapp.aiworkbench.android.AIWorkbenchActivity;
import com.cscjapp.aiworkbench.api.WorkbenchLaunchRequest;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PlaygroundInstrumentationTest {
  @Test
  public void applicationInstallsSdkAndDashboardActionsAreBound() {
    assertTrue(
        InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext()
            instanceof SampleApp);
    assertTrue(AIWorkbench.isInstalled());
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(
          activity -> {
            assertBound(activity, R.id.buttonOffline);
            assertBound(activity, R.id.buttonCodeAgent);
            assertBound(activity, R.id.buttonReal);
            assertBound(activity, R.id.buttonScenarios);
            assertBound(activity, R.id.buttonSettings);
            assertBound(activity, R.id.buttonClearLogs);
          });
    }
  }

  @Test
  public void codeAgentEntryOpensOptInWorkbench() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> activity.findViewById(R.id.buttonCodeAgent).performClick());
      AIWorkbenchActivity workbench = waitFor(AIWorkbenchActivity.class);
      assertNotNull(workbench);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(workbench::finish);
    }
  }

  @Test
  public void codeAgentOfflineModeCompletesPlanReadEditVerifyQualityAndFinalize()
      throws Exception {
    PlaygroundRuntime runtime =
        PlaygroundRuntime.get(
            InstrumentationRegistry.getInstrumentation().getTargetContext());
    runtime.workspace().writeAtomic(
        "code-agent-demo.txt", "Code Agent 待读取和修改的初始内容。\n", true);
    runtime.setLatestArtifact("");
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> activity.findViewById(R.id.buttonCodeAgent).performClick());
      AIWorkbenchActivity workbench = waitFor(AIWorkbenchActivity.class);
      assertNotNull(workbench);
      ArtifactPreviewActivity preview = waitFor(ArtifactPreviewActivity.class);
      assertNotNull(preview);
      assertTrue(
          runtime
              .workspace()
              .read("code-agent-demo.txt")
              .contains("Code Agent 闭环修改完成"));
      String logs = runtime.logs();
      assertTrue(logs.contains("plan_task"));
      assertTrue(logs.contains("read_file"));
      assertTrue(logs.contains("rewrite"));
      assertTrue(logs.contains("verify_workspace"));
      assertTrue(logs.contains("quality_review"));
      assertTrue(logs.contains("finalize_task"));
      InstrumentationRegistry.getInstrumentation().runOnMainSync(preview::finish);
      InstrumentationRegistry.getInstrumentation().runOnMainSync(workbench::finish);
    }
  }

  @Test
  public void offlineEntryOpensInteractiveGenericWorkbench() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> activity.findViewById(R.id.buttonOffline).performClick());
      AIWorkbenchActivity workbench = waitFor(AIWorkbenchActivity.class);
      assertNotNull(workbench);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                assertBound(workbench, com.cscjapp.aiworkbench.android.R.id.aiw_ivBack);
                assertBound(workbench, com.cscjapp.aiworkbench.android.R.id.aiw_tvClearHistory);
                assertBound(workbench, com.cscjapp.aiworkbench.android.R.id.aiw_btnExplainCode);
                assertBound(workbench, com.cscjapp.aiworkbench.android.R.id.aiw_btnContextQuick);
                assertBound(workbench, com.cscjapp.aiworkbench.android.R.id.aiw_btnSend);
                workbench.finish();
              });
    }
  }

  @Test
  public void offlineConversationExecutesToolAndOpensCreatedArtifact() throws Exception {
    PlaygroundRuntime runtime =
        PlaygroundRuntime.get(
            InstrumentationRegistry.getInstrumentation().getTargetContext());
    runtime.setLatestArtifact("");
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> activity.findViewById(R.id.buttonOffline).performClick());
      AIWorkbenchActivity workbench = waitFor(AIWorkbenchActivity.class);
      assertNotNull(workbench);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                android.widget.EditText input =
                    workbench.findViewById(
                        com.cscjapp.aiworkbench.android.R.id.aiw_etDemand);
                input.setText("请创建文件并写入离线 instrumentation 测试内容");
                workbench
                    .findViewById(com.cscjapp.aiworkbench.android.R.id.aiw_btnSend)
                    .performClick();
              });
      ArtifactPreviewActivity preview = waitFor(ArtifactPreviewActivity.class);
      assertNotNull(preview);
      assertTrue(!runtime.latestArtifact().isEmpty());
      File artifact = runtime.workspace().resolveSafely(runtime.latestArtifact());
      assertTrue(artifact.isFile());
      InstrumentationRegistry.getInstrumentation().runOnMainSync(preview::finish);
      InstrumentationRegistry.getInstrumentation().runOnMainSync(workbench::finish);
    }
  }

  @Test
  public void dashboardCanOpenSettingsAndHostCanOpenSandboxArtifact() throws Exception {
    PlaygroundRuntime runtime =
        PlaygroundRuntime.get(
            InstrumentationRegistry.getInstrumentation().getTargetContext());
    PlaygroundHost host = new PlaygroundHost(runtime);
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> activity.findViewById(R.id.buttonSettings).performClick());
    }
    ModelSettingsActivity settings = waitFor(ModelSettingsActivity.class);
    assertNotNull(settings);
    InstrumentationRegistry.getInstrumentation().runOnMainSync(settings::finish);

    host.openArtifact("README.md");
    ArtifactPreviewActivity preview = waitFor(ArtifactPreviewActivity.class);
    assertNotNull(preview);
    InstrumentationRegistry.getInstrumentation().runOnMainSync(preview::finish);
  }

  @Test
  public void openFailsImmediatelyWhenSdkHasNotBeenInstalled() throws Exception {
    Field config = AIWorkbench.class.getDeclaredField("config");
    config.setAccessible(true);
    Object installed = config.get(null);
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      scenario.onActivity(
          activity -> {
            try {
              config.set(null, null);
              AIWorkbench.open(
                  activity,
                  WorkbenchLaunchRequest.builder(PlaygroundRuntime.DEFINITION_ID).build());
            } catch (Throwable error) {
              failure.set(error);
            } finally {
              try {
                config.set(null, installed);
              } catch (IllegalAccessException error) {
                failure.set(error);
              }
            }
          });
      assertTrue(failure.get() instanceof IllegalStateException);
      assertTrue(failure.get().getMessage().contains("Application.onCreate"));
    } finally {
      config.set(null, installed);
    }
    assertTrue(AIWorkbench.isInstalled());
  }

  private static void assertBound(Activity activity, int id) {
    View view = activity.findViewById(id);
    assertNotNull(view);
    assertTrue("missing click listener for " + id, view.hasOnClickListeners());
  }

  private static <T extends Activity> T waitFor(Class<T> type) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    long deadline = System.currentTimeMillis() + 5000L;
    while (System.currentTimeMillis() < deadline && result.get() == null) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                Collection<Activity> activities =
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for (Activity activity : activities) {
                  if (type.isInstance(activity)) {
                    result.set(type.cast(activity));
                    break;
                  }
                }
              });
      if (result.get() == null) Thread.sleep(50L);
    }
    return result.get();
  }
}
