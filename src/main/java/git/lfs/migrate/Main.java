package git.lfs.migrate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.internal.Nullable;
import org.eclipse.jgit.lib.*;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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
    processRepository(cmd.src, cmd.dst, cmd.lfs != null ? new URL(cmd.lfs) : null, cmd.threads, cmd.suffixes.toArray(new String[cmd.suffixes.size()]));
    log.info("Convert time: {}", System.currentTimeMillis() - time);
  }

  public static void processRepository(@NotNull File srcPath, @NotNull File dstPath, @Nullable URL lfs, int threads, @NotNull String... suffixes) throws IOException, InterruptedException {
    removeDirectory(dstPath);
    dstPath.mkdirs();

    final Repository srcRepo = new FileRepositoryBuilder()
        .setMustExist(true)
        .setGitDir(srcPath).build();
    final Repository dstRepo = new FileRepositoryBuilder()
        .setMustExist(false)
        .setGitDir(dstPath).build();
    final GitConverter converter = new GitConverter(srcRepo, dstPath, lfs, suffixes);
    try {
      dstRepo.create(true);
      // Load all revision list.
      log.info("Reading full objects list...");
      final SimpleDirectedGraph<TaskKey, DefaultEdge> graph = loadTaskGraph(converter, srcRepo);
      final int totalObjects = graph.vertexSet().size();
      log.info("Found objects: {}", totalObjects);

      final ConcurrentMap<TaskKey, ObjectId> converted = new ConcurrentHashMap<>();
      log.info("Converting object without dependencies in " + threads + " threads...", totalObjects);
      processMultipleThreads(converter, graph, dstRepo, converted, threads);

      log.info("Converting graph in single thread...");
      processSingleThread(converter, graph, dstRepo, converted);

      // Validate result
      if (converted.size() != totalObjects) {
        throw new IllegalStateException();
      }

      log.info("Recreating refs...");
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

  private static void processMultipleThreads(@NotNull GitConverter converter, @NotNull SimpleDirectedGraph<TaskKey, DefaultEdge> graph, Repository dstRepo, @NotNull ConcurrentMap<TaskKey, ObjectId> converted, int threads) throws IOException, InterruptedException {
    final Deque<TaskKey> queue = new ConcurrentLinkedDeque<>();
    for (TaskKey vertex : graph.vertexSet()) {
      if (graph.outgoingEdgesOf(vertex).isEmpty()) {
        queue.add(vertex);
      }
    }
    graph.removeAllVertices(queue);
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    try (ProgressReporter reporter = new ProgressReporter("completed", queue.size())) {
      final List<Future<?>> jobs = new ArrayList<>(threads);
      for (int i = 0; i < threads; ++i) {
        jobs.add(pool.submit(() -> {
          try {
            final ObjectInserter inserter = dstRepo.newObjectInserter();
            while (!queue.isEmpty()) {
              final TaskKey taskKey = queue.poll();
              final ObjectId objectId = converter.convertTask(taskKey).convert(inserter, converted::get);
              converted.put(taskKey, objectId);
              reporter.increment();
            }
            inserter.flush();
          } catch (IOException e) {
            rethrow(e);
          }
        }));
      }
      for (Future<?> job : jobs) {
        try {
          job.get();
        } catch (ExecutionException e) {
          rethrow(e.getCause());
        }
      }
    } finally {
      pool.shutdown();
    }
  }

  private static void processSingleThread(@NotNull GitConverter converter, @NotNull SimpleDirectedGraph<TaskKey, DefaultEdge> graph, Repository dstRepo, @NotNull Map<TaskKey, ObjectId> converted) throws IOException {
    try (ProgressReporter reporter = new ProgressReporter("completed", graph.vertexSet().size())) {
      final Deque<TaskKey> queue = new ArrayDeque<>();
      for (TaskKey vertex : graph.vertexSet()) {
        if (graph.outgoingEdgesOf(vertex).isEmpty()) {
          queue.add(vertex);
        }
      }
      final ObjectInserter inserter = dstRepo.newObjectInserter();
      while (!queue.isEmpty()) {
        final TaskKey taskKey = queue.pop();
        final ObjectId objectId = converter.convertTask(taskKey).convert(inserter, converted::get);
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
        reporter.increment();
      }
      inserter.flush();
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
    try (ProgressReporter reporter = new ProgressReporter("found", -1)) {
      final Map<String, Ref> refs = repository.getAllRefs();
      final SimpleDirectedGraph<TaskKey, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
      final Deque<TaskKey> queue = new ArrayDeque<>();
      // Heads
      for (Ref ref : refs.values()) {
        final TaskKey taskKey = new TaskKey(GitConverter.TaskType.Simple, ref.getObjectId());
        if (graph.addVertex(taskKey)) {
          queue.add(taskKey);
          reporter.increment();
        }
      }
      while (!queue.isEmpty()) {
        final TaskKey taskKey = queue.pop();
        for (TaskKey depend : converter.convertTask(taskKey).depends()) {
          if (graph.addVertex(depend)) {
            queue.add(depend);
            reporter.increment();
          }
          graph.addEdge(taskKey, depend);
        }
      }
      return graph;
    }
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

  public static class ProgressReporter implements AutoCloseable {
    private static final long DELAY = TimeUnit.SECONDS.toMillis(1);
    private final long total;
    @NotNull
    private final AtomicLong current = new AtomicLong(0);
    @NotNull
    private final AtomicLong lastTime = new AtomicLong(0);
    @NotNull
    private final String prefix;

    public ProgressReporter(@NotNull String prefix, long total) {
      this.prefix = prefix;
      this.total = total;
    }

    public void increment() {
      final long last = current.incrementAndGet();
      final long oldTime = lastTime.get();
      final long newTime = System.currentTimeMillis();
      if (oldTime < newTime - DELAY) {
        if (lastTime.compareAndSet(oldTime, newTime)) {
          print(last);
        }
      }
    }

    @Override
    public void close() {
      print(current.get());
    }

    private void print(long current) {
      log.info("  " + prefix + ": " + current + (total >= 0 ? "/" + total : ""));
    }

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
    @Parameter(names = {"-t", "--threads"}, description = "Thread count", required = false)
    private int threads = Runtime.getRuntime().availableProcessors();

    @Parameter(description = "LFS file suffixes")
    @NotNull
    private List<String> suffixes = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, description = "Show help", help = true)
    private boolean help = false;
  }

}
