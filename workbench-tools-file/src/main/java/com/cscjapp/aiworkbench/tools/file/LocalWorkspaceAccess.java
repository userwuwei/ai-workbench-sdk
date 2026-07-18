package com.cscjapp.aiworkbench.tools.file;

import com.cscjapp.aiworkbench.api.WorkspaceAccess;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class LocalWorkspaceAccess implements WorkspaceAccess {
  private final String id;
  private final File root;

  public LocalWorkspaceAccess(String id, File root) throws IOException {
    this.id = id;
    this.root = root.getCanonicalFile();
    if (!this.root.isDirectory()) throw new IOException("workspace root is not a directory");
  }

  public String workspaceId() {
    return id;
  }

  public File rootDirectory() {
    return root;
  }

  public File resolveSafely(String path) throws IOException {
    if (path == null || path.trim().isEmpty()) throw new IOException("path required");
    File candidate = new File(path);
    if (!candidate.isAbsolute()) candidate = new File(root, path);
    File canonical = candidate.getCanonicalFile();
    String rp = root.getPath(), cp = canonical.getPath();
    if (!cp.equals(rp) && !cp.startsWith(rp + File.separator))
      throw new IOException("path_outside_workspace");
    return canonical;
  }

  public List<String> list(String path) throws IOException {
    File d = resolveSafely(path == null || path.isEmpty() ? "." : path);
    if (!d.isDirectory()) throw new IOException("not_a_directory");
    File[] fs = d.listFiles();
    if (fs == null) return Collections.emptyList();
    Arrays.sort(fs, Comparator.comparing(File::getName));
    List<String> out = new ArrayList<>();
    for (File f : fs) out.add(relative(f) + (f.isDirectory() ? "/" : ""));
    return out;
  }

  public String read(String path) throws IOException {
    File f = resolveSafely(path);
    if (!f.isFile()) throw new IOException("not_a_file");
    byte[] data = new byte[(int) f.length()];
    try (InputStream in = new FileInputStream(f)) {
      int off = 0, n;
      while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
    }
    return new String(data, StandardCharsets.UTF_8);
  }

  public void writeAtomic(String path, String content, boolean overwrite) throws IOException {
    File f = resolveSafely(path);
    if (f.isDirectory()) throw new IOException("target_is_directory");
    if (f.exists() && !overwrite) throw new IOException("file_already_exists");
    File parent = f.getParentFile();
    if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory())
      throw new IOException("cannot_create_parent");
    File tmp = File.createTempFile(".aiw-", ".tmp", parent);
    File backup = new File(parent, tmp.getName() + ".bak");
    boolean moved = false, targetBackedUp = false;
    try {
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.getFD().sync();
      }
      if (f.exists()) {
        if (!f.renameTo(backup)) throw new IOException("cannot_backup_target");
        targetBackedUp = true;
      }
      if (!tmp.renameTo(f)) {
        if (targetBackedUp) backup.renameTo(f);
        throw new IOException("atomic_replace_failed");
      }
      moved = true;
      if (targetBackedUp && !backup.delete()) backup.deleteOnExit();
    } finally {
      if (!moved) tmp.delete();
      if (!moved && targetBackedUp && !f.exists()) backup.renameTo(f);
    }
  }

  public boolean exists(String path) throws IOException {
    return resolveSafely(path).exists();
  }

  public boolean isDirectory(String path) throws IOException {
    return resolveSafely(path).isDirectory();
  }

  private String relative(File f) throws IOException {
    String p = f.getCanonicalPath(), rp = root.getPath();
    return p.equals(rp) ? "." : p.substring(rp.length() + 1).replace(File.separatorChar, '/');
  }
}
