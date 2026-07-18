package com.cscjapp.aiworkbench.api;

public final class ThemeConfig {
  private final String title, disclaimer;
  private final int accentColor;

  public ThemeConfig(String title, String disclaimer, int accentColor) {
    this.title = title;
    this.disclaimer = disclaimer;
    this.accentColor = accentColor;
  }

  public String title() {
    return title;
  }

  public String disclaimer() {
    return disclaimer;
  }

  public int accentColor() {
    return accentColor;
  }

  public static ThemeConfig defaults() {
    return new ThemeConfig("AI 工作台", "AI 生成内容仅供参考", 0xff60a5fa);
  }
}
