package com.cscjapp.aiworkbench.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class WorkbenchPlanLogTest {
  @Test
  public void compactLogCanReconstructPlanWithoutSourceOrFullArguments() {
    String log =
        WorkbenchViewModel.formatPlanLog(
            3,
            "completed",
            "完成移动端游戏",
            "index.html；game.js",
            Arrays.asList("读取项目", "完成实现", "运行验证", "质量审查", "结束任务"),
            "finalize",
            "finalize_task",
            "syntax_check 1通过；browser_test 1通过/1失败",
            "validator_passed");

    assertTrue(log.contains("[任务计划][run=3][event=completed]"));
    assertTrue(log.contains("步骤=读取项目 → 完成实现"));
    assertTrue(log.contains("browser_test 1通过/1失败"));
    assertFalse(log.contains("<html>"));
    assertTrue(log.length() <= 1000);
  }
}
