package git.lfs.migrate;

import com.beust.jcommander.internal.Nullable;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.FileRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jetbrains.annotations.NotNull;

import java.io.*;
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
  @Nullable
  private final URL lfs;
  @NotNull
  private final String[] suffixes;
  @NotNull
  private final File basePath;
  private final File tempPath;

  public GitConverter(@NotNull File basePath, @Nullable URL lfs, @NotNull String[] suffixes) {
    this.basePath = basePath;
    this.suffixes = suffixes.clone();
    this.lfs = lfs;

    tempPath = new File(basePath, "lfs/tmp");
    tempPath.mkdirs();
  }

  @NotNull
  public ConvertTask convertTask(@NotNull ObjectReader reader, @NotNull TaskKey key) throws IOException {
    switch (key.getType()) {
      case Simple: {
        if (!reader.has(key.getObjectId())) {
          return keepMissingTask(key.getObjectId());
        }
        final RevObject revObject = new RevWalk(reader).parseAny(key.getObjectId());
        if (revObject instanceof RevCommit) {
          return convertCommitTask((RevCommit) revObject);
        }
        if (revObject instanceof RevTree) {
          return convertTreeTask(reader, revObject, false);
        }
        if (revObject instanceof RevBlob) {
          return copyTask(reader, revObject);
        }
        if (revObject instanceof RevTag) {
          return convertTagTask((RevTag) revObject);
        }
        throw new IllegalStateException("Unsupported object type: " + key + " (" + revObject.getClass().getName() + ")");
      }
      case Root: {
        final RevObject revObject = new RevWalk(reader).parseAny(key.getObjectId());
        if (revObject instanceof RevTree) {
          return convertTreeTask(reader, revObject, true);
        }
        throw new IllegalStateException("Unsupported object type: " + key + " (" + revObject.getClass().getName() + ")");
      }
      case Attribute:
        return createAttributesTask(reader, key.getObjectId());
      case UploadLfs:
        return convertLfsTask(reader, key.getObjectId());
      default:
        throw new IllegalStateException("Unknwon task key type: " + key.getType());
    }
  }

  private ConvertTask keepMissingTask(@NotNull ObjectId objectId) {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException {
        return objectId;
      }
    };
  }

  @NotNull
  private ConvertTask convertTagTask(@NotNull RevTag revObject) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() {
        return Collections.singletonList(
            new TaskKey(TaskType.Simple, revObject.getObject())
        );
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException {
        final ObjectId id = resolver.resolve(TaskType.Simple, revObject.getObject());
        final TagBuilder builder = new TagBuilder();
        builder.setMessage(revObject.getFullMessage());
        builder.setTag(revObject.getTagName());
        builder.setTagger(revObject.getTaggerIdent());
        builder.setObjectId(id, revObject.getObject().getType());
        return inserter.insert(builder);
      }
    };
  }

  @NotNull
  private ConvertTask convertCommitTask(@NotNull RevCommit revObject) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() {
        List<TaskKey> result = new ArrayList<>();
        for (RevCommit parent : revObject.getParents()) {
          result.add(new TaskKey(TaskType.Simple, parent));
        }
        result.add(new TaskKey(TaskType.Root, revObject.getTree()));
        return result;
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException {
        final CommitBuilder builder = new CommitBuilder();
        builder.setAuthor(revObject.getAuthorIdent());
        builder.setCommitter(revObject.getCommitterIdent());
        builder.setEncoding(revObject.getEncoding());
        builder.setMessage(revObject.getFullMessage());
        // Set parents
        for (RevCommit oldParent : revObject.getParents()) {
          builder.addParentId(resolver.resolve(TaskType.Simple, oldParent));
        }
        // Set tree
        builder.setTreeId(resolver.resolve(TaskType.Root, revObject.getTree()));
        return inserter.insert(builder);
      }
    };
  }

  @NotNull
  private ConvertTask convertTreeTask(@NotNull ObjectReader reader, @NotNull ObjectId id, boolean rootTree) {
    return new ConvertTask() {
      @NotNull
      private List<GitTreeEntry> getEntries() throws IOException {
        final List<GitTreeEntry> entries = new ArrayList<>();
        final CanonicalTreeParser treeParser = new CanonicalTreeParser(null, reader, id);
        boolean needAttributes = rootTree;
        while (!treeParser.eof()) {
          final FileMode fileMode = treeParser.getEntryFileMode();
          final TaskType blobTask;
          if (needAttributes && treeParser.getEntryPathString().equals(GIT_ATTRIBUTES)) {
            blobTask = TaskType.Attribute;
            needAttributes = false;
          } else if ((fileMode.getObjectType() == Constants.OBJ_BLOB) && (fileMode == FileMode.REGULAR_FILE) && matchFilename(treeParser.getEntryPathString())) {
            blobTask = TaskType.UploadLfs;
          } else {
            blobTask = TaskType.Simple;
          }
          entries.add(new GitTreeEntry(fileMode, new TaskKey(blobTask, treeParser.getEntryObjectId()), treeParser.getEntryPathString()));
          treeParser.next();
        }
        if (needAttributes && suffixes.length > 0) {
          entries.add(new GitTreeEntry(FileMode.REGULAR_FILE, new TaskKey(TaskType.Attribute, ObjectId.zeroId()), GIT_ATTRIBUTES));
        }
        return entries;
      }

      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        final List<TaskKey> result = new ArrayList<>();
        for (GitTreeEntry entry : getEntries()) {
          result.add(entry.getTaskKey());
        }
        return result;
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException {
        final List<GitTreeEntry> entries = getEntries();
        // Create new tree.
        Collections.sort(entries);
        final TreeFormatter treeBuilder = new TreeFormatter();
        for (GitTreeEntry entry : entries) {
          treeBuilder.append(entry.getFileName(), entry.getFileMode(), resolver.resolve(entry.getTaskKey()));
        }
        new ObjectChecker().checkTree(treeBuilder.toByteArray());
        return inserter.insert(treeBuilder);
      }
    };
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
  private ConvertTask convertLfsTask(@NotNull ObjectReader reader, @Nullable ObjectId id) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException {
        final MessageDigest md;
        try {
          md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
          throw new IllegalStateException(e);
        }
        // Create LFS stream.
        final File tmpFile = new File(tempPath, id.getName());
        final ObjectLoader loader = reader.open(id, Constants.OBJ_BLOB);
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
        final File lfsFile = new File(basePath, "lfs/objects/" + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash);
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

        return inserter.insert(Constants.OBJ_BLOB, pointer.toString().getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  private void upload(@NotNull String hash, long size, @NotNull File file) throws IOException {
    if (lfs == null) {
      return;
    }

    HttpClient client = new HttpClient();
    PostMethod post = new PostMethod(new URL(lfs, "objects").toString());
    post.addRequestHeader("Accept", "application/vnd.git-lfs+json");
    if (lfs.getUserInfo() != null) {
      post.addRequestHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(lfs.getUserInfo().getBytes(StandardCharsets.UTF_8)));
    }

    try (StringWriter writer = new StringWriter()) {
      JsonWriter json = new JsonWriter(writer);
      json.beginObject();
      json.name("oid").value(hash);
      json.name("size").value(size);
      json.endObject();
      post.setRequestEntity(new StringRequestEntity(writer.toString(), "application/vnd.git-lfs+json", "UTF-8"));
    }

    final int postStatus = client.executeMethod(post);
    if (postStatus == HttpStatus.SC_OK) {
      // Already uploaded.
      return;
    }
    if (postStatus != HttpStatus.SC_ACCEPTED) {
      throw new HttpError(post, "I can't get details for object " + hash + " uploading");
    }

    final JsonObject upload;
    try (Reader reader = new InputStreamReader(post.getResponseBodyAsStream(), StandardCharsets.UTF_8)) {
      JsonElement json = new JsonParser().parse(reader);
      upload = json.getAsJsonObject().get("_links").getAsJsonObject().get("upload").getAsJsonObject();
    }

    // Upload data.
    PutMethod put = new PutMethod(upload.get("href").getAsString());
    for (Map.Entry<String, JsonElement> header : upload.get("header").getAsJsonObject().entrySet()) {
      put.addRequestHeader(header.getKey(), header.getValue().getAsString());
    }
    put.setRequestEntity(new FileRequestEntity(file, "application/octet-stream"));
    final int putStatus = client.executeMethod(put);
    if (putStatus != HttpStatus.SC_OK) {
      throw new HttpError(post, "I can't upload object " + hash);
    }
  }

  @NotNull
  private ConvertTask createAttributesTask(@NotNull final ObjectReader reader, @Nullable ObjectId id) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException {
        final Set<String> attributes = new TreeSet<>();
        for (String suffix : suffixes) {
          attributes.add("*" + suffix + "\tfilter=lfs diff=lfs merge=lfs -crlf");
        }
        final ByteArrayOutputStream blob = new ByteArrayOutputStream();
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(openAttributes(reader, id), StandardCharsets.UTF_8))) {
          while (true) {
            String line = bufferedReader.readLine();
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
      }
    };
  }

  private ConvertTask copyTask(@NotNull ObjectReader reader, @NotNull ObjectId id) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException {
        final ObjectLoader loader = reader.open(id);
        try (ObjectStream stream = loader.openStream()) {
          inserter.insert(loader.getType(), loader.getSize(), stream);
        }
        return id;
      }
    };
  }

  @NotNull
  private InputStream openAttributes(@NotNull ObjectReader reader, @Nullable ObjectId id) throws IOException {
    if (ObjectId.zeroId().equals(id)) {
      return new ByteArrayInputStream(new byte[0]);
    }
    return reader.open(id, Constants.OBJ_BLOB).openStream();
  }

  public enum TaskType {
    Simple, Root, Attribute, UploadLfs,
  }

  public interface ConvertResolver {
    @NotNull
    ObjectId resolve(@NotNull TaskKey key);

    @NotNull
    default ObjectId resolve(@NotNull TaskType type, @NotNull ObjectId objectId) {
      return resolve(new TaskKey(type, objectId));
    }
  }

  public interface ConvertTask {
    @NotNull
    Iterable<TaskKey> depends() throws IOException;

    @NotNull
    ObjectId convert(@NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver) throws IOException;
  }

}
