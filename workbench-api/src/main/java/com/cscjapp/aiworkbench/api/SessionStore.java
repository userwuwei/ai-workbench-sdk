package com.cscjapp.aiworkbench.api;

public interface SessionStore {
  SessionSnapshot loadLatest(String definitionId, String workspaceId);

  void save(SessionSnapshot snapshot);

  void clear(String definitionId, String workspaceId);
}
