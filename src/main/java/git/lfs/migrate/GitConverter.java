package git.lfs.migrate;

import git.path.PathMatcher;
import git.path.WildcardHelper;
import org.apache.commons.codec.binary.Hex;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.fnmatch.FileNameMatcher;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.mapdb.serializer.SerializerJava;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bozaro.gitlfs.common.data.Meta;
import ru.bozaro.gitlfs.pointer.Pointer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Converter for git objects.
 * Created by bozaro on 09.06.15.
 */
public class GitConverter {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(GitConverter.class);
  @NotNull
  private static final String GIT_ATTRIBUTES = ".gitattributes";
  @NotNull
  private final String[] globs;
  @NotNull
  private final PathMatcher[] matchers;
  @NotNull
  private final DB cache;
  @NotNull
  private final Path basePath;
  @NotNull
  private final Path tempPath;
  @NotNull
  private final HTreeMap<String, MetaData> cacheMeta;

  public GitConverter(@NotNull DB cache, @NotNull Path basePath, @NotNull String[] globs) throws IOException, InvalidPatternException {
    this.basePath = basePath;
    this.cache = cache;
    this.globs = globs.clone();
    this.matchers = convertGlobs(globs);
    Arrays.sort(globs);

    for (String glob : globs) {
      new FileNameMatcher(glob, '/');
    }

    tempPath = basePath.resolve("lfs/tmp");
    Files.createDirectories(tempPath);
    //noinspection unchecked
    cacheMeta = cache.<String, MetaData>hashMap("meta")
        .keySerializer(Serializer.STRING)
        .valueSerializer(new SerializerJava())
        .createOrOpen();
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
          return convertTreeTask(reader, revObject, Objects.requireNonNull(key.getPath()));
        }
        if (revObject instanceof RevBlob) {
          return copyTask(reader, revObject);
        }
        if (revObject instanceof RevTag) {
          return convertTagTask((RevTag) revObject);
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
      public ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException {
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
            new TaskKey(TaskType.Simple, "", revObject.getObject())
        );
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException {
        final ObjectId id = resolver.resolve(TaskType.Simple, "", revObject.getObject());
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
          result.add(new TaskKey(TaskType.Simple, "", parent));
        }
        result.add(new TaskKey(TaskType.Simple, "", revObject.getTree()));
        return result;
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException {
        final CommitBuilder builder = new CommitBuilder();
        builder.setAuthor(revObject.getAuthorIdent());
        builder.setCommitter(revObject.getCommitterIdent());
        builder.setEncoding(revObject.getEncoding());
        builder.setMessage(revObject.getFullMessage());
        // Set parents
        for (RevCommit oldParent : revObject.getParents()) {
          builder.addParentId(resolver.resolve(TaskType.Simple, "", oldParent));
        }
        // Set tree
        builder.setTreeId(resolver.resolve(TaskType.Simple, "", revObject.getTree()));
        return inserter.insert(builder);
      }
    };
  }

  @NotNull
  private ConvertTask convertTreeTask(@NotNull ObjectReader reader, @NotNull ObjectId id, @NotNull String path) {
    return new ConvertTask() {
      @NotNull
      private List<GitTreeEntry> getEntries() throws IOException {
        final List<GitTreeEntry> entries = new ArrayList<>();
        final CanonicalTreeParser treeParser = new CanonicalTreeParser(null, reader, id);
        boolean needAttributes = path.isEmpty();
        while (!treeParser.eof()) {
          final FileMode fileMode = treeParser.getEntryFileMode();
          final TaskType blobTask;
          final String pathTask;
          if (needAttributes && treeParser.getEntryPathString().equals(GIT_ATTRIBUTES)) {
            blobTask = TaskType.Attribute;
            pathTask = null;
            needAttributes = false;
          } else if (isFile(fileMode) && matchFilename(path + "/" + treeParser.getEntryPathString())) {
            blobTask = TaskType.UploadLfs;
            pathTask = null;
          } else {
            blobTask = TaskType.Simple;
            pathTask = path + "/" + treeParser.getEntryPathString();
          }
          entries.add(new GitTreeEntry(fileMode, new TaskKey(blobTask, pathTask, treeParser.getEntryObjectId()), treeParser.getEntryPathString()));
          treeParser.next();
        }
        if (needAttributes && globs.length > 0) {
          entries.add(new GitTreeEntry(FileMode.REGULAR_FILE, new TaskKey(TaskType.Attribute, null, ObjectId.zeroId()), GIT_ATTRIBUTES));
        }
        return entries;
      }

      private boolean isFile(@NotNull FileMode fileMode) {
        return (fileMode.getObjectType() == Constants.OBJ_BLOB) && ((fileMode.getBits() & FileMode.TYPE_MASK) == FileMode.TYPE_FILE);
      }

      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return getEntries().stream().map(GitTreeEntry::getTaskKey).collect(Collectors.toList());
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException {
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

  @NotNull
  private static PathMatcher[] convertGlobs(String[] globs) throws InvalidPatternException {
    final PathMatcher[] matchers = new PathMatcher[globs.length];
    for (int i = 0; i < globs.length; ++i) {
      String glob = globs[i];
      if (!glob.contains("/")) {
        glob = "**/" + glob;
      }
      matchers[i] = WildcardHelper.createMatcher(glob, true);
    }
    return matchers;
  }

  public boolean matchFilename(@NotNull String fileName) {
    if (!fileName.startsWith("/")) {
      throw new IllegalStateException("Unexpected file name: " + fileName);
    }
    for (PathMatcher matcher : matchers) {
      if (WildcardHelper.isMatch(matcher, fileName)) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  private ConvertTask convertLfsTask(@NotNull ObjectReader reader, @NotNull ObjectId id) throws IOException {
    return new ConvertTask() {
      @NotNull
      @Override
      public Iterable<TaskKey> depends() throws IOException {
        return Collections.emptyList();
      }

      @NotNull
      @Override
      public ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException {
        final ObjectLoader loader = reader.open(id, Constants.OBJ_BLOB);
        // Is empty blob (see #21)?
        if (loader.getSize() == 0) {
          if (dstRepo.hasObject(id)) return id;
          return copy(inserter, loader);
        }
        // Is object already converted?
        if (isLfsPointer(loader)) {
          if (dstRepo.hasObject(id)) return id;
          return copy(inserter, loader);
        }
        final String hash = (uploader == null) ? createLocalFile(id, loader) : createRemoteFile(id, loader, uploader);
        // Create pointer.
        StringWriter pointer = new StringWriter();
        pointer.write("version https://git-lfs.github.com/spec/v1\n");
        pointer.write("oid sha256:" + hash + "\n");
        pointer.write("size " + loader.getSize() + "\n");

        return inserter.insert(Constants.OBJ_BLOB, pointer.toString().getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  @NotNull
  private ObjectId copy(@NotNull ObjectInserter inserter, @NotNull ObjectLoader loader) throws IOException {
    try (ObjectStream stream = loader.openStream()) {
      return inserter.insert(loader.getType(), loader.getSize(), stream);
    }
  }

  @NotNull
  private String createRemoteFile(@NotNull ObjectId id, @NotNull ObjectLoader loader, @NotNull Uploader uploader) throws IOException {
    // Create LFS stream.
    final String hash;
    final MetaData cached = cacheMeta.get(id.name());
    long size = 0;
    if (cached == null) {
      final MessageDigest md = createSha256();
      try (InputStream istream = loader.openStream()) {
        byte[] buffer = new byte[0x10000];
        while (true) {
          int read = istream.read(buffer);
          if (read <= 0) break;
          md.update(buffer, 0, read);
          size += read;
        }
      }
      hash = new String(Hex.encodeHex(md.digest(), true));
      cacheMeta.put(id.name(), new MetaData(hash, size));
      cache.commit();
    } else {
      hash = cached.oid;
      size = cached.size;
    }
    uploader.upload(id, new Meta(hash, size));
    return hash;
  }

  @NotNull
  private String createLocalFile(@NotNull ObjectId id, @NotNull ObjectLoader loader) throws IOException {
    // Create LFS stream.
    final Path tmpFile = tempPath.resolve(UUID.randomUUID().toString());
    final MessageDigest md = createSha256();
    int size = 0;
    try (InputStream istream = loader.openStream();
         OutputStream ostream = Files.newOutputStream(tmpFile)) {
      byte[] buffer = new byte[0x10000];
      while (true) {
        int read = istream.read(buffer);
        if (read <= 0) break;
        ostream.write(buffer, 0, read);
        md.update(buffer, 0, read);
        size += read;
      }
    }
    final String hash = new String(Hex.encodeHex(md.digest(), true));
    cacheMeta.putIfAbsent(id.name(), new MetaData(hash, size));
    cache.commit();
    // Rename file.
    final Path lfsFile = basePath.resolve("lfs/objects/" + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash);
    Files.createDirectories(lfsFile.getParent());
    if (Files.exists(lfsFile)) {
      try {
        Files.delete(tmpFile);
      } catch (IOException e) {
        log.warn("Can't delete temporary file: {}", lfsFile.toAbsolutePath());
      }
    } else {
      Files.move(tmpFile, lfsFile, StandardCopyOption.ATOMIC_MOVE);
    }
    return hash;
  }

  @NotNull
  private static MessageDigest createSha256() {
    // Prepare for hash calculation
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean isLfsPointer(@NotNull ObjectLoader loader) {
    return loader.getSize() <= ru.bozaro.gitlfs.pointer.Constants.POINTER_MAX_SIZE
        && Pointer.parsePointer(loader.getBytes()) != null;
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
      public ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException {
        final Set<String> attributes = new TreeSet<>();
        for (String glob : globs) {
          attributes.add(glob + "\tfilter=lfs diff=lfs merge=lfs -text");
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
      public ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException {
        if (dstRepo.hasObject(id)) return id;
        return copy(inserter, reader.open(id));
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
    Simple(true),
    Attribute(false),
    UploadLfs(false);

    TaskType(boolean needPath) {
      this.needPath = needPath;
    }

    private final boolean needPath;

    public boolean needPath() {
      return needPath;
    }
  }

  public interface ConvertResolver {
    @NotNull
    ObjectId resolve(@NotNull TaskKey key);

    @NotNull
    default ObjectId resolve(@NotNull TaskType type, @Nullable String path, @NotNull ObjectId objectId) {
      return resolve(new TaskKey(type, path, objectId));
    }
  }

  public interface ConvertTask {
    @NotNull
    Iterable<TaskKey> depends() throws IOException;

    @NotNull
    ObjectId convert(@NotNull Repository dstRepo, @NotNull ObjectInserter inserter, @NotNull ConvertResolver resolver, @Nullable Uploader uploader) throws IOException;
  }

  @FunctionalInterface
  public interface Uploader {
    void upload(@NotNull ObjectId oid, @NotNull Meta meta);
  }

  private static class MetaData implements Serializable {
    private final String oid;
    private final long size;

    MetaData(String oid, long size) {
      this.oid = oid;
      this.size = size;
    }
  }
}
