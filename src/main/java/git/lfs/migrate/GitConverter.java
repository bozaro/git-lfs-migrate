package git.lfs.migrate;

import com.beust.jcommander.internal.Nullable;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

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
  @NotNull
  private final String[] suffixes;
  @NotNull
  private final ObjectInserter inserter;

  public GitConverter(@NotNull Repository srcRepo, @NotNull Repository dstRepo, @NotNull String... suffixes) {
    this.srcRepo = srcRepo;
    this.dstRepo = dstRepo;
    this.revWalk = new RevWalk(srcRepo);
    this.inserter = dstRepo.newObjectInserter();
    this.suffixes = suffixes.clone();
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
    return converted.computeIfAbsent("lfs:" + id.getName(), new Function<String, ObjectId>() {
      @Override
      public ObjectId apply(String s) {
        try {
          // todo: Доделать.
          return copy(id);
        } catch (IOException e) {
          rethrow(e);
          return ObjectId.zeroId();
        }
      }
    });
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
