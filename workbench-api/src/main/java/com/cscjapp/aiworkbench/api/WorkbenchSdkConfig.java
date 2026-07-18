package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class WorkbenchSdkConfig {
  private final Map<String, WorkbenchFactory> factories;
  private final ModelConfigProvider modelConfigProvider;
  private final AccessPolicy accessPolicy;
  private final ThemeConfig themeConfig;
  private final SessionStore sessionStore;

  private WorkbenchSdkConfig(Builder b) {
    factories = Collections.unmodifiableMap(new LinkedHashMap<>(b.factories));
    modelConfigProvider = b.modelConfigProvider;
    accessPolicy = b.accessPolicy;
    themeConfig = b.themeConfig;
    sessionStore = b.sessionStore;
    if (factories.isEmpty()) throw new IllegalStateException("at least one factory required");
    if (modelConfigProvider == null)
      throw new IllegalStateException("modelConfigProvider required");
    if (accessPolicy == null) throw new IllegalStateException("accessPolicy required");
    if (themeConfig == null) throw new IllegalStateException("themeConfig required");
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, WorkbenchFactory> factories() {
    return factories;
  }

  public ModelConfigProvider modelConfigProvider() {
    return modelConfigProvider;
  }

  public AccessPolicy accessPolicy() {
    return accessPolicy;
  }

  public ThemeConfig themeConfig() {
    return themeConfig;
  }

  public SessionStore sessionStore() {
    return sessionStore;
  }

  public static final class Builder {
    private final Map<String, WorkbenchFactory> factories = new LinkedHashMap<>();
    private ModelConfigProvider modelConfigProvider;
    private AccessPolicy accessPolicy = (a, r, c) -> c.allow();
    private ThemeConfig themeConfig = ThemeConfig.defaults();
    private SessionStore sessionStore;

    public Builder registerFactory(String id, WorkbenchFactory f) {
      if (id == null || id.trim().isEmpty() || f == null)
        throw new IllegalArgumentException("factory id/value required");
      if (factories.put(id.trim(), f) != null)
        throw new IllegalStateException("duplicate factory: " + id);
      return this;
    }

    public Builder modelConfigProvider(ModelConfigProvider v) {
      modelConfigProvider = v;
      return this;
    }

    public Builder accessPolicy(AccessPolicy v) {
      accessPolicy = v;
      return this;
    }

    public Builder themeConfig(ThemeConfig v) {
      themeConfig = v;
      return this;
    }

    public Builder sessionStore(SessionStore v) {
      sessionStore = v;
      return this;
    }

    public WorkbenchSdkConfig build() {
      return new WorkbenchSdkConfig(this);
    }
  }
}
