package git.lfs.migrate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.internal.Nullable;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
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

    final Repository srcRepo = new FileRepositoryBuilder()
        .setMustExist(true)
        .setGitDir(srcPath).build();
    final Repository dstRepo = new FileRepositoryBuilder()
        .setMustExist(false)
        .setGitDir(dstPath).build();
    final GitConverter converter = new GitConverter(srcRepo, dstRepo, lfs, suffixes);
    try {
      dstRepo.create(true);
      // Load all revision list.
      log.info("Reading full objects list...");
      final SimpleDirectedGraph<TaskKey, DefaultEdge> graph = loadTaskGraph(converter, srcRepo);
      final int totalObjects = graph.vertexSet().size();
      log.info("Found objects: {}", totalObjects);

      log.info("Converting...", totalObjects);
      final Deque<TaskKey> queue = new ArrayDeque<>();
      for (TaskKey vertex : graph.vertexSet()) {
        if (graph.outgoingEdgesOf(vertex).isEmpty()) {
          queue.add(vertex);
        }
      }
      final Map<TaskKey, ObjectId> converted = new HashMap<>();
      while (!queue.isEmpty()) {
        final TaskKey taskKey = queue.pop();
        final ObjectId objectId = converter.convertTask(taskKey).convert(converted::get);
        converted.put(taskKey, objectId);

        final List<TaskKey> sources = new ArrayList<>();
        for (DefaultEdge edge : graph.incomingEdgesOf(taskKey)) {
          sources.add(graph.getEdgeSource(edge));
        }

        graph.removeVertex(taskKey);
        for (TaskKey source : sources) {
          if (graph.outgoingEdgesOf(source).isEmpty()) {
            queue.add(source);
          }
        }
      }
      if (converted.size() != totalObjects) {
        throw new IllegalStateException();
      }

      log.info("Recreating refs...", totalObjects);
      for (Map.Entry<String, Ref> ref : srcRepo.getAllRefs().entrySet()) {
        RefUpdate refUpdate = dstRepo.updateRef(ref.getKey());
        final ObjectId oldId = ref.getValue().getObjectId();
        final ObjectId newId = converted.get(new TaskKey(GitConverter.TaskType.Simple, oldId));
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

  private static SimpleDirectedGraph<TaskKey, DefaultEdge> loadTaskGraph(@NotNull GitConverter converter, @NotNull Repository repository) throws IOException {
    final Map<String, Ref> refs = repository.getAllRefs();
    final SimpleDirectedGraph<TaskKey, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
    // Heads
    final Deque<TaskKey> queue = new ArrayDeque<>();
    for (Ref ref : refs.values()) {
      final TaskKey taskKey = new TaskKey(GitConverter.TaskType.Simple, ref.getObjectId());
      if (graph.addVertex(taskKey)) {
        queue.add(taskKey);
      }
    }
    while (!queue.isEmpty()) {
      final TaskKey taskKey = queue.pop();
      for (TaskKey depend : converter.convertTask(taskKey).depends()) {
        if (graph.addVertex(depend)) {
          queue.add(depend);
        }
        graph.addEdge(taskKey, depend);
      }
    }
    return graph;
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
