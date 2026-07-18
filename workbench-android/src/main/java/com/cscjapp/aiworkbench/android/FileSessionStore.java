package com.cscjapp.aiworkbench.android;

import android.content.Context;
import android.system.Os;
import com.cscjapp.aiworkbench.api.SessionSnapshot;
import com.cscjapp.aiworkbench.api.SessionStore;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Atomic, workspace-keyed default session persistence. */
public final class FileSessionStore implements SessionStore {
  private final File directory;
  private final Gson gson = new Gson();

  public FileSessionStore(Context context) {
    directory = new File(context.getFilesDir(), "ai_workbench_sessions");
    if (!directory.exists()) directory.mkdirs();
  }

  @Override
  public synchronized SessionSnapshot loadLatest(String definitionId, String workspaceId) {
    File file = file(definitionId, workspaceId);
    if (!file.isFile()) return null;
    try {
      return gson.fromJson(read(file), SessionSnapshot.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  @Override
  public synchronized void save(SessionSnapshot snapshot) {
    if (snapshot == null || (!directory.exists() && !directory.mkdirs())) return;
    File target = file(snapshot.definitionId(), snapshot.workspaceId());
    File temp = new File(target.getPath() + ".tmp");
    try {
      byte[] data = gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
      try (FileOutputStream output = new FileOutputStream(temp, false)) {
        output.write(data);
        output.flush();
        output.getFD().sync();
      }
      Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
    } catch (Exception ignored) {
      temp.delete();
    }
  }

  @Override
  public synchronized void clear(String definitionId, String workspaceId) {
    file(definitionId, workspaceId).delete();
  }

  private File file(String definitionId, String workspaceId) {
    return new File(directory, sha(definitionId + "\n" + workspaceId) + ".json");
  }

  private static String read(File file) throws Exception {
    try (InputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static String sha(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte item : digest) result.append(String.format("%02x", item));
      return result.toString();
    } catch (Exception ignored) {
      return Integer.toHexString(value.hashCode());
    }
  }
}
