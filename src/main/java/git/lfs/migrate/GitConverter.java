package git.lfs.migrate;

import com.beust.jcommander.internal.Nullable;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.codec.binary.Hex;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Converter for git objects.
 * Created by bozaro on 09.06.15.
 */
public class GitConverter {
  @NotNull
  private static final String GIT_ATTRIBUTES = ".gitattributes";
  @NotNull
  private final Repository srcRepo;
  @NotNull
  private final Repository dstRepo;
  @NotNull
  private final RevWalk revWalk;
  @NotNull
  private final Map<String, ObjectId> converted = new HashMap<>();
  @Nullable
  private final URL lfs;
  @NotNull
  private final String[] suffixes;
  @NotNull
  private final File tmpDir;
  @NotNull
  private final ObjectInserter inserter;

  public GitConverter(@NotNull Repository srcRepo, @NotNull Repository dstRepo, @Nullable URL lfs, @NotNull String[] suffixes) {
    this.srcRepo = srcRepo;
    this.dstRepo = dstRepo;
    this.revWalk = new RevWalk(srcRepo);
    this.inserter = dstRepo.newObjectInserter();
    this.suffixes = suffixes.clone();
    this.lfs = lfs;

    tmpDir = new File(dstRepo.getDirectory(), "lfs/tmp");
    tmpDir.mkdirs();
  }

  @NotNull
  public ObjectId convert(@NotNull ObjectId id) throws IOException {
    return converted.computeIfAbsent(id.getName(), key -> {
      if (!srcRepo.hasObject(id)) {
        return id;
      }
      try {
        final RevObject revObject = revWalk.parseAny(id);
        if (revObject instanceof RevCommit) {
          return convertCommit((RevCommit) revObject);
        }
        if (revObject instanceof RevTree) {
          return convertTree(revObject, false);
        }
        if (revObject instanceof RevBlob) {
          return copy(id);
        }
        throw new IllegalStateException("Unsupported object type: " + id.getName() + " (" + revObject.getClass().getName() + ")");
      } catch (IOException e) {
        rethrow(e);
        return ObjectId.zeroId();
      }
    });
  }

  public void flush() throws IOException {
    inserter.flush();
  }

  public static void rethrow(final Throwable exception) {
    class EvilThrower<T extends Throwable> {
      @SuppressWarnings("unchecked")
      private void sneakyThrow(Throwable exception) throws T {
        throw (T) exception;
      }
    }
    new EvilThrower<RuntimeException>().sneakyThrow(exception);
  }

  @NotNull
  private ObjectId convertCommit(@NotNull RevCommit revObject) throws IOException {
    final CommitBuilder builder = new CommitBuilder();
    builder.setAuthor(revObject.getAuthorIdent());
    builder.setCommitter(revObject.getCommitterIdent());
    builder.setEncoding(revObject.getEncoding());
    builder.setMessage(revObject.getFullMessage());
    boolean modified = false;
    // Convert parents
    for (RevCommit oldParent : revObject.getParents()) {
      ObjectId newParent = convert(oldParent);
      modified |= !newParent.equals(oldParent);
      builder.addParentId(newParent);
    }
    // Convert tree
    final ObjectId newTree = convertTreeRoot(revObject.getTree());
    modified |= !newTree.equals(revObject.getTree());
    builder.setTreeId(newTree);
    // If not changed - keep old commit
    if (!modified) {
      return copy(revObject);
    }
    return inserter.insert(builder);
  }

  @NotNull
  private ObjectId convertTreeRoot(@NotNull ObjectId id) throws IOException {
    return converted.computeIfAbsent("root:" + id.getName(), key -> {
      try {
        return convertTree(id, true);
      } catch (IOException e) {
        rethrow(e);
        return ObjectId.zeroId();
      }
    });
  }

  @NotNull
  private ObjectId convertTree(@NotNull ObjectId id, boolean rootTree) throws IOException {
    final List<GitTreeEntry> entries = new ArrayList<>();
    final CanonicalTreeParser treeParser = new CanonicalTreeParser(null, srcRepo.newObjectReader(), id);
    boolean modified = false;
    boolean needAttributes = rootTree;
    while (!treeParser.eof()) {
      final FileMode fileMode = treeParser.getEntryFileMode();
      final ObjectId blobId;
      if (needAttributes && treeParser.getEntryPathString().equals(GIT_ATTRIBUTES)) {
        blobId = createAttributes(treeParser.getEntryObjectId());
        needAttributes = false;
      } else if ((fileMode.getObjectType() == Constants.OBJ_BLOB) && (fileMode == FileMode.REGULAR_FILE) && matchFilename(treeParser.getEntryPathString())) {
        blobId = convertLFS(treeParser.getEntryObjectId());
      } else {
        blobId = convert(treeParser.getEntryObjectId());
      }
      modified |= !blobId.equals(treeParser.getEntryObjectId());
      entries.add(new GitTreeEntry(fileMode, blobId, treeParser.getEntryPathString()));
      treeParser.next();
    }
    if (needAttributes) {
      entries.add(new GitTreeEntry(FileMode.REGULAR_FILE, createAttributes(null), GIT_ATTRIBUTES));
      modified = true;
    }
    // Keed old tree if not modified.
    if (!modified) {
      return copy(id);
    }
    // Create new tree.
    Collections.sort(entries);
    final TreeFormatter treeBuilder = new TreeFormatter();
    for (GitTreeEntry entry : entries) {
      treeBuilder.append(entry.getFileName(), entry.getFileMode(), entry.getObjectId());
    }
    new ObjectChecker().checkTree(treeBuilder.toByteArray());
    return inserter.insert(treeBuilder);
  }

  private boolean matchFilename(@NotNull String fileName) {
    for (String suffix : suffixes) {
      if (fileName.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  private ObjectId convertLFS(@Nullable ObjectId id) throws IOException {
    return converted.computeIfAbsent("lfs:" + id.getName(), s -> {
      try {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        // Create LFS stream.
        final File tmpFile = new File(tmpDir, id.getName());
        final ObjectLoader loader = srcRepo.open(id, Constants.OBJ_BLOB);
        try (InputStream istream = loader.openStream();
             OutputStream ostream = new FileOutputStream(tmpFile)) {
          byte[] buffer = new byte[0x10000];
          while (true) {
            int size = istream.read(buffer);
            if (size <= 0) break;
            ostream.write(buffer, 0, size);
            md.update(buffer, 0, size);
          }
        }
        String hash = new String(Hex.encodeHex(md.digest(), true));
        // Upload file.
        upload(hash, loader.getSize(), tmpFile);
        // Rename file.
        final File lfsFile = new File(dstRepo.getDirectory(), "lfs/objects/" + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash);
        lfsFile.getParentFile().mkdirs();
        if (lfsFile.exists()) {
          tmpFile.delete();
        } else if (!tmpFile.renameTo(lfsFile)) {
          throw new IOException("Can't rename file: " + tmpFile + " -> " + lfsFile);
        }
        // Create pointer.
        StringWriter pointer = new StringWriter();
        pointer.write("version https://git-lfs.github.com/spec/v1\n");
        pointer.write("oid sha256:" + hash + "\n");
        pointer.write("size " + loader.getSize() + "\n");
        pointer.write("\n");

        return inserter.insert(Constants.OBJ_BLOB, pointer.toString().getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        rethrow(e);
        return ObjectId.zeroId();
      } catch (NoSuchAlgorithmException e) {
        rethrow(e);
        return ObjectId.zeroId();
      }
    });
  }

  private void upload(@NotNull String hash, long size, @NotNull File file) throws IOException {
    if (lfs == null) {
      return;
    }

    HttpURLConnection conn = (HttpURLConnection) new URL(lfs, "objects").openConnection();
    conn.setRequestMethod("POST");
    conn.addRequestProperty("Accept", "application/vnd.git-lfs+json");
    if (lfs.getUserInfo() != null) {
      conn.addRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(lfs.getUserInfo().getBytes(StandardCharsets.UTF_8)));
    }
    conn.addRequestProperty("Content-Type", "application/vnd.git-lfs+json");

    conn.setDoOutput(true);
    try (Writer writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
      JsonWriter json = new JsonWriter(writer);
      json.beginObject();
      json.name("oid").value(hash);
      json.name("size").value(size);
      json.endObject();
    }
    if (conn.getResponseCode() == 200) {
      // Already uploaded.
      return;
    }

    final JsonObject upload;
    try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
      JsonElement json = new JsonParser().parse(reader);
      upload = json.getAsJsonObject().get("_links").getAsJsonObject().get("upload").getAsJsonObject();
    }

    // Upload data.
    conn = (HttpURLConnection) new URL(upload.get("href").getAsString()).openConnection();
    conn.setRequestMethod("PUT");
    for (Map.Entry<String, JsonElement> header : upload.get("header").getAsJsonObject().entrySet()) {
      conn.addRequestProperty(header.getKey(), header.getValue().getAsString());
    }
    conn.setDoOutput(true);
    conn.setFixedLengthStreamingMode(size);
    try (OutputStream ostream = conn.getOutputStream();
         InputStream istream = new FileInputStream(file)) {
      byte[] buffer = new byte[0x10000];
      while (true) {
        int len = istream.read(buffer);
        if (len <= 0) break;
        ostream.write(buffer, 0, len);
      }
    }
    conn.getInputStream().close();
  }

  @NotNull
  private ObjectId createAttributes(@Nullable ObjectId id) throws IOException {
    return converted.computeIfAbsent("attr:" + (id != null ? id.getName() : "default"), s -> {
      try {
        Set<String> attributes = new TreeSet<>();
        for (String suffix : suffixes) {
          attributes.add("*" + suffix + "\tfilter=lfs diff=lfs merge=lfs -crlf");
        }
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(openAttributes(id), StandardCharsets.UTF_8))) {
          while (true) {
            String line = reader.readLine();
            if (line == null) break;
            if (!attributes.remove(line)) {
              blob.write(line.getBytes(StandardCharsets.UTF_8));
              blob.write('\n');
            }
          }
        }
        for (String line : attributes) {
          blob.write(line.getBytes(StandardCharsets.UTF_8));
          blob.write('\n');
        }
        return inserter.insert(Constants.OBJ_BLOB, blob.toByteArray());
      } catch (IOException e) {
        rethrow(e);
        return ObjectId.zeroId();
      }
    });
  }

  private ObjectId copy(@NotNull ObjectId id) throws IOException {
    if (!dstRepo.hasObject(id)) {
      ObjectLoader loader = srcRepo.open(id);
      try (ObjectStream stream = loader.openStream()) {
        inserter.insert(loader.getType(), loader.getSize(), stream);
      }
    }
    return id;
  }

  @NotNull
  private InputStream openAttributes(@Nullable ObjectId id) throws IOException {
    if (id == null) {
      return new ByteArrayInputStream(new byte[0]);
    }
    return srcRepo.open(id, Constants.OBJ_BLOB).openStream();
  }
}
