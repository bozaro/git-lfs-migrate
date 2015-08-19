package git.lfs.migrate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.internal.Nullable;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Entry point.
 *
 * @author a.navrotskiy
 */
public class Main {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(@NotNull String[] args) throws IOException, InterruptedException {
    final CmdArgs cmd = new CmdArgs();
    final JCommander jc = new JCommander(cmd);
    jc.parse(args);
    if (cmd.help) {
      jc.usage();
      return;
    }
    final long time = System.currentTimeMillis();
    processRepository(cmd.src, cmd.dst, cmd.lfs != null ? new URL(cmd.lfs) : null, cmd.suffixes.toArray(new String[cmd.suffixes.size()]));
    log.info("Convert time: {}", System.currentTimeMillis() - time);
  }

  public static void processRepository(@NotNull File srcPath, @NotNull File dstPath, @Nullable URL lfs, @NotNull String... suffixes) throws IOException {
    removeDirectory(dstPath);
    dstPath.mkdirs();

    Repository srcRepo = new FileRepositoryBuilder()
        .setMustExist(true)
        .setGitDir(srcPath).build();
    Repository dstRepo = new FileRepositoryBuilder()
        .setMustExist(false)
        .setGitDir(dstPath).build();
    try {
      dstRepo.create(true);
      // Load all revision list.
      log.info("Reading full revisions list...");
      final List<ObjectId> revisions = loadCommitList(srcRepo);
      log.info("Found revisions: {}", revisions.size());
      int i = 0;
      GitConverter converter = new GitConverter(srcRepo, dstRepo, lfs, suffixes);
      for (ObjectId revision : revisions) {
        i += 1;
        ObjectId newId = converter.convert(revision);
        converter.flush();
        log.info("  convert revision: {} -> {} ({}/{})", revision.getName(), newId.getName(), i, revisions.size());
      }
      for (Map.Entry<String, Ref> ref : srcRepo.getAllRefs().entrySet()) {
        RefUpdate refUpdate = dstRepo.updateRef(ref.getKey());
        final ObjectId oldId = ref.getValue().getObjectId();
        final ObjectId newId = converter.convert(oldId);
        refUpdate.setNewObjectId(newId);
        refUpdate.update();
        log.info("  convert ref: {} -> {} ({})", oldId.getName(), newId.getName(), ref.getKey());
      }
    } finally {
      dstRepo.close();
      srcRepo.close();
    }
  }

  private static void removeDirectory(@NotNull File path) throws IOException {
    if (path.exists()) {
      Files.walkFileTree(path.toPath(), new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    }
  }

  private static List<ObjectId> loadCommitList(@NotNull Repository repository) throws IOException {
    final Map<String, Ref> refs = repository.getAllRefs();
    final SimpleDirectedGraph<ObjectId, DefaultEdge> commitGraph = new SimpleDirectedGraph<>(DefaultEdge.class);
    // Heads
    final Set<ObjectId> heads = new HashSet<>();
    for (Ref ref : refs.values()) {
      if (commitGraph.addVertex(ref.getObjectId())) {
        heads.add(ref.getObjectId());
      }
    }
    // Relations
    {
      final Queue<ObjectId> queue = new ArrayDeque<>();
      queue.addAll(heads);
      final RevWalk revWalk = new RevWalk(repository);
      while (true) {
        final ObjectId id = queue.poll();
        if (id == null) {
          break;
        }
        final RevCommit commit = revWalk.parseCommit(id);
        if (commit != null) {
          for (RevCommit parent : commit.getParents()) {
            if (commitGraph.addVertex(parent.getId())) {
              queue.add(parent.getId());
            }
            commitGraph.addEdge(id, parent.getId());
          }
        }
      }
    }
    // Create revisions list
    final List<ObjectId> result = new ArrayList<>();
    {
      final Deque<ObjectId> queue = new ArrayDeque<>();
      // Heads
      for (ObjectId id : heads) {
        if (commitGraph.incomingEdgesOf(id).isEmpty()) {
          queue.push(id);
        }
      }
      while (!queue.isEmpty()) {
        final ObjectId id = queue.pop();
        if (!commitGraph.containsVertex(id)) {
          continue;
        }
        final Set<DefaultEdge> edges = commitGraph.outgoingEdgesOf(id);
        if (!edges.isEmpty()) {
          queue.push(id);
          for (DefaultEdge edge : edges) {
            queue.push(commitGraph.getEdgeTarget(edge));
          }
          commitGraph.removeAllEdges(new HashSet<>(edges));
        } else {
          commitGraph.removeVertex(id);
          result.add(id);
        }
      }
    }
    // Validate list.
    {
      final RevWalk revWalk = new RevWalk(repository);
      final Set<ObjectId> competed = new HashSet<>();
      for (ObjectId id : result) {
        final RevCommit commit = revWalk.parseCommit(id);
        if (commit != null) {
          for (RevCommit parent : commit.getParents()) {
            if (!competed.contains(parent.getId())) {
              throw new IllegalStateException();
            }
          }
        }
        competed.add(id);
      }
    }
    return result;
  }

  public static class CmdArgs {
    @Parameter(names = {"-s", "--source"}, description = "Source repository", required = true)
    @NotNull
    private File src;
    @Parameter(names = {"-d", "--destination"}, description = "Destination repository", required = true)
    @NotNull
    private File dst;
    @Parameter(names = {"-l", "--lfs"}, description = "LFS URL", required = false)
    @Nullable
    private String lfs;

    @Parameter(description = "LFS file suffixes")
    @NotNull
    private List<String> suffixes = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, description = "Show help", help = true)
    private boolean help = false;
  }

}
