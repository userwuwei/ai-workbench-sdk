package com.cscjapp.aiworkbench.api;

import java.util.*;

public final class UserDecisionRequest {
  public static final class Option {
    private final String id, label;

    public Option(String id, String label) {
      this.id = id;
      this.label = label;
    }

    public String id() {
      return id;
    }

    public String label() {
      return label;
    }
  }

  private final String title, message;
  private final List<Option> options;
  private final boolean cancellable;

  public UserDecisionRequest(
      String title, String message, List<Option> options, boolean cancellable) {
    this.title = title == null ? "" : title;
    this.message = message == null ? "" : message;
    this.options =
        options == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(options));
    this.cancellable = cancellable;
  }

  public String title() {
    return title;
  }

  public String message() {
    return message;
  }

  public List<Option> options() {
    return options;
  }

  public boolean cancellable() {
    return cancellable;
  }
}
