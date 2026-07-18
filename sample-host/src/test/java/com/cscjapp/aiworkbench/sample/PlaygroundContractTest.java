package com.cscjapp.aiworkbench.sample;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public final class PlaygroundContractTest {
  @Test
  public void manifestStartsSampleApplicationAndRegistersPlaygroundScreens() throws Exception {
    String manifest = read("src/main/AndroidManifest.xml");
    assertTrue(manifest.contains("android:name=\".SampleApp\""));
    assertTrue(manifest.contains("android:name=\".ModelSettingsActivity\""));
    assertTrue(manifest.contains("android:name=\".ScenarioLabActivity\""));
    assertTrue(manifest.contains("android:name=\".ArtifactPreviewActivity\""));
  }

  @Test
  public void dashboardContainsAllIndependentDevelopmentEntrypoints() throws Exception {
    String layout = read("src/main/res/layout/activity_main.xml");
    assertTrue(layout.contains("@+id/buttonOffline"));
    assertTrue(layout.contains("@+id/buttonCodeAgent"));
    assertTrue(layout.contains("@+id/buttonReal"));
    assertTrue(layout.contains("@+id/buttonScenarios"));
    assertTrue(layout.contains("@+id/buttonSettings"));
    assertTrue(layout.contains("@+id/textLogs"));
  }

  @Test
  public void playgroundKeepsGenericAndOptInCodeAgentDefinitionsSeparate() throws Exception {
    String application = read("src/main/java/com/cscjapp/aiworkbench/sample/SampleApp.java");
    String definition =
        read("src/main/java/com/cscjapp/aiworkbench/sample/PlaygroundDefinition.java");
    assertTrue(application.contains("PlaygroundRuntime.DEFINITION_ID"));
    assertTrue(application.contains("PlaygroundRuntime.CODE_DEFINITION_ID"));
    assertTrue(definition.contains("if (codeAgentMode)"));
    assertTrue(definition.contains("CodeAgentPreset.builder"));
    assertTrue(definition.contains("validators = Collections.emptyList()"));
  }

  @Test
  public void realModelValidationReportsPreciseConfigurationErrors() {
    assertEquals(
        "Base URL 不能为空",
        PlaygroundRuntime.realConfigurationError("", "model", "0.2"));
    assertEquals(
        "Base URL 必须是有效的 HTTP/HTTPS 地址",
        PlaygroundRuntime.realConfigurationError("ftp://example.com", "model", "0.2"));
    assertEquals(
        "Model 不能为空",
        PlaygroundRuntime.realConfigurationError("http://127.0.0.1:8080/v1", "", "0.2"));
    assertEquals(
        "Temperature 必须在 0～2 之间",
        PlaygroundRuntime.realConfigurationError("https://example.com/v1", "model", "2.1"));
    assertEquals(
        "",
        PlaygroundRuntime.realConfigurationError(
            "https://example.com/v1", "model", "0.2"));
  }

  private static String read(String path) throws Exception {
    return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
  }
}
