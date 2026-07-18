package com.cscjapp.aiworkbench.api;

import java.io.*;
import java.util.List;

public interface WorkspaceAccess {
  String workspaceId();

  File rootDirectory();

  File resolveSafely(String path) throws IOException;

  List<String> list(String path) throws IOException;

  String read(String path) throws IOException;

  void writeAtomic(String path, String content, boolean overwrite) throws IOException;

  boolean exists(String path) throws IOException;

  boolean isDirectory(String path) throws IOException;
}
