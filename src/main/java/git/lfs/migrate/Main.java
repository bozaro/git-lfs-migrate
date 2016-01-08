package git.lfs.migrate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.commons.httpclient.HttpStatus;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bozaro.gitlfs.client.AuthHelper;
import ru.bozaro.gitlfs.client.BatchUploader;
import ru.bozaro.gitlfs.client.Client;
import ru.bozaro.gitlfs.client.auth.AuthProvider;
import ru.bozaro.gitlfs.client.auth.BasicAuthProvider;
import ru.bozaro.gitlfs.client.exceptions.ForbiddenException;
import ru.bozaro.gitlfs.common.data.*;
import ru.bozaro.gitlfs.common.data.Error;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point.
 *
 * @author a.navrotskiy
 */
public class Main {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(@NotNull String[] args) throws IOException, InterruptedException, ExecutionException {
    final CmdArgs cmd = new CmdArgs();
    final JCommander jc = new JCommander(cmd);
    jc.parse(args);
    if (cmd.help) {
      jc.usage();
      return;
    }
    final long time = System.currentTimeMillis();
    final AuthProvider auth;
    if (cmd.lfs != null) {
      auth = new BasicAuthProvider(URI.create(cmd.lfs));
    } else if (cmd.git != null) {
      auth = AuthHelper.create(cmd.git);
    } else {
      auth = null;
    }
    if (!checkLfsAuthenticate(auth)) {
      return;
    }
    if (cmd.checkLfs) {
      if (auth == null) {
        log.error("Git LFS server is not defined.");
      }
      return;
    }
    processRepository(cmd.src, cmd.dst, cmd.cache, auth, cmd.writeThreads, cmd.uploadThreads, cmd.suffixes.toArray(new String[cmd.suffixes.size()]));
    log.info("Convert time: {}", System.currentTimeMillis() - time);
  }

  private static boolean checkLfsAuthenticate(@Nullable AuthProvider auth) throws IOException {
    if (auth == null)
      return true;
    final Meta meta = new Meta("0123456789012345678901234567890123456789012345678901234567890123", 42);
    Client client = new Client(auth);
    try {
      BatchRes response = client.postBatch(
          new BatchReq(Operation.Upload, Collections.singletonList(
              meta
          ))
      );
      if (response.getObjects().size() != 1) {
        log.error("LFS server: Invalid response for test batch request");
      }
      Error error = response.getObjects().get(0).getError();
      if (error != null) {
        if (error.getCode() == HttpStatus.SC_FORBIDDEN) {
          log.error("LFS server: Upload access denied");
        } else {
          log.error("LFS server: Upload denied with error: " + error.getMessage());
        }
      }
      log.info("LFS server: OK");
      return true;
    } catch (ForbiddenException e) {
      log.error("LFS server: Access denied", e);
      return false;
    } catch (IOException e) {
      log.info("LFS server: Batch API request exception", e);
    }
    try {
      client.getMeta(meta.getOid());
      log.error("LFS server: Unsupported batch API");
    } catch (IOException ignored) {
      log.error("LFS server: Invalid base URL");
    }
    return false;
  }

  public static void processRepository(@NotNull File srcPath, @NotNull File dstPath, @NotNull File cachePath, @Nullable AuthProvider auth, int writeThreads, int uploadThreads, @NotNull String... suffixes) throws IOException, InterruptedException, ExecutionException {
    removeDirectory(dstPath);
    dstPath.mkdirs();

    final Repository srcRepo = new FileRepositoryBuilder()
        .setMustExist(true)
        .setGitDir(srcPath).build();
    final Repository dstRepo = new FileRepositoryBuilder()
        .setMustExist(false)
        .setGitDir(dstPath).build();

    final GitConverter converter = new GitConverter(cachePath, dstPath, suffixes);
    try {
      dstRepo.create(true);
      // Load all revision list.
      log.info("Reading full objects list...");
      final SimpleDirectedGraph<TaskKey, DefaultEdge> graph = loadTaskGraph(converter, srcRepo);
      final int totalObjects = graph.vertexSet().size();
      log.info("Found objects: {}", totalObjects);

      final ConcurrentMap<TaskKey, ObjectId> converted = new ConcurrentHashMap<>();
      log.info("Converting object without dependencies in " + writeThreads + " threads...", totalObjects);
      try (HttpUploader uploader = createHttpUploader(srcRepo, auth, uploadThreads)) {
        processMultipleThreads(converter, graph, srcRepo, dstRepo, converted, uploader, writeThreads);
      }

      log.info("Converting graph in single thread...");
      processSingleThread(converter, graph, srcRepo, dstRepo, converted);

      // Validate result
      if (converted.size() != totalObjects) {
        throw new IllegalStateException();
      }

      log.info("Recreating refs...");
      for (Map.Entry<String, Ref> ref : srcRepo.getAllRefs().entrySet()) {
        RefUpdate refUpdate = dstRepo.updateRef(ref.getKey());
        final ObjectId oldId = ref.getValue().getObjectId();
        final ObjectId newId = converted.get(new TaskKey(GitConverter.TaskType.Simple, "", oldId));
        refUpdate.setNewObjectId(newId);
        refUpdate.update();
        log.info("  convert ref: {} -> {} ({})", oldId.getName(), newId.getName(), ref.getKey());
      }
    } finally {
      dstRepo.close();
      srcRepo.close();
    }
  }

  @Nullable
  private static HttpUploader createHttpUploader(@NotNull Repository repository, @Nullable AuthProvider auth, int uploadThreads) {
    return auth == null ? null : new HttpUploader(repository, auth, uploadThreads);
  }

  private static void processMultipleThreads(@NotNull GitConverter converter, @NotNull SimpleDirectedGraph<TaskKey, DefaultEdge> graph, @NotNull Repository srcRepo, @NotNull Repository dstRepo, @NotNull ConcurrentMap<TaskKey, ObjectId> converted, @Nullable HttpUploader uploader, int threads) throws IOException, InterruptedException {
    final Deque<TaskKey> queue = new ConcurrentLinkedDeque<>();
    for (TaskKey vertex : graph.vertexSet()) {
      if (graph.outgoingEdgesOf(vertex).isEmpty()) {
        queue.add(vertex);
      }
    }
    graph.removeAllVertices(queue);
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    try (ProgressReporter reporter = new ProgressReporter("processed", queue.size(), uploader)) {
      final AtomicBoolean done = new AtomicBoolean(false);
      final List<Future<?>> jobs = new ArrayList<>(threads);
      for (int i = 0; i < threads; ++i) {
        jobs.add(pool.submit(() -> {
          try {
            final ObjectInserter inserter = dstRepo.newObjectInserter();
            final ObjectReader reader = srcRepo.newObjectReader();
            while (!done.get()) {
              final TaskKey taskKey = queue.poll();
              if (taskKey == null) break;
              final ObjectId objectId = converter.convertTask(reader, taskKey).convert(inserter, converted::get, uploader);
              converted.put(taskKey, objectId);
              reporter.increment();
            }
            inserter.flush();
          } catch (IOException e) {
            rethrow(e);
          } finally {
            done.set(true);
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

  private static void processSingleThread(@NotNull GitConverter converter, @NotNull SimpleDirectedGraph<TaskKey, DefaultEdge> graph, @NotNull Repository srcRepo, @NotNull Repository dstRepo, @NotNull Map<TaskKey, ObjectId> converted) throws IOException {
    try (ProgressReporter reporter = new ProgressReporter("completed", graph.vertexSet().size(), null)) {
      final Deque<TaskKey> queue = new ArrayDeque<>();
      for (TaskKey vertex : graph.vertexSet()) {
        if (graph.outgoingEdgesOf(vertex).isEmpty()) {
          queue.add(vertex);
        }
      }
      final ObjectInserter inserter = dstRepo.newObjectInserter();
      final ObjectReader reader = srcRepo.newObjectReader();
      while (!queue.isEmpty()) {
        final TaskKey taskKey = queue.pop();
        final ObjectId objectId = converter.convertTask(reader, taskKey).convert(inserter, converted::get, (oid, meta) -> {
          throw new UnsupportedOperationException("Not supported on this repository convert stage");
        });
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
    try (ProgressReporter reporter = new ProgressReporter("found", -1, null)) {
      final Map<String, Ref> refs = repository.getAllRefs();
      final SimpleDirectedGraph<TaskKey, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
      final Deque<TaskKey> queue = new ArrayDeque<>();
      // Heads
      for (Ref ref : refs.values()) {
        final TaskKey taskKey = new TaskKey(GitConverter.TaskType.Simple, "", ref.getObjectId());
        if (graph.addVertex(taskKey)) {
          queue.add(taskKey);
          reporter.increment();
        }
      }
      final ObjectReader reader = repository.newObjectReader();
      while (!queue.isEmpty()) {
        final TaskKey taskKey = queue.pop();
        for (TaskKey depend : converter.convertTask(reader, taskKey).depends()) {
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

  public static class HttpUploader implements GitConverter.Uploader, AutoCloseable {
    @NotNull
    private final ThreadLocal<ObjectReader> readers = new ThreadLocal<>();
    @NotNull
    private final ExecutorService pool;
    @NotNull
    private final Repository repository;
    @NotNull
    private final BatchUploader uploader;
    @NotNull
    private final Collection<CompletableFuture<?>> futures = new LinkedBlockingQueue<>();
    @NotNull
    private final AtomicInteger finished = new AtomicInteger();
    @NotNull
    private final AtomicInteger total = new AtomicInteger();

    public HttpUploader(@NotNull Repository repository, @NotNull AuthProvider auth, int threads) {
      this.pool = Executors.newFixedThreadPool(threads);
      this.uploader = new BatchUploader(new Client(auth), pool);
      this.repository = repository;
    }

    @Override
    public void upload(@NotNull ObjectId oid, @NotNull Meta meta) {
      total.incrementAndGet();
      futures.add(uploader.upload(meta, () -> getReader().open(oid).openStream()).thenAccept((m) -> finished.incrementAndGet()));
    }

    @NotNull
    private ObjectReader getReader() {
      ObjectReader reader = readers.get();
      if (reader == null) {
        reader = repository.newObjectReader();
        readers.set(reader);
      }
      return reader;
    }

    @Override
    public void close() throws ExecutionException, InterruptedException {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).get();
      pool.shutdown();
    }

    public int getTotal() {
      return total.get();
    }

    public int getFinished() {
      return finished.get();
    }
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
    @Nullable
    private final HttpUploader uploader;

    public ProgressReporter(@NotNull String prefix, long total, @Nullable HttpUploader uploader) {
      this.prefix = prefix;
      this.total = total;
      this.uploader = uploader;
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
      String message = "  " + prefix + ": " + current + (total >= 0 ? "/" + total : "");
      if (uploader != null) {
        message += ", uploaded: " + uploader.getFinished() + "/" + uploader.getTotal();
      }
      log.info(message);
    }

  }

  public static class CmdArgs {
    @Parameter(names = {"-s", "--source"}, description = "Source repository", required = true)
    @NotNull
    private File src;
    @Parameter(names = {"-d", "--destination"}, description = "Destination repository", required = true)
    @NotNull
    private File dst;
    @Parameter(names = {"-c", "--cache"}, description = "Source repository", required = false)
    @NotNull
    private File cache = new File(".");
    @Parameter(names = {"-g", "--git"}, description = "GIT repository url (ignored with --lfs parameter)", required = false)
    @Nullable
    private String git;
    @Parameter(names = {"-l", "--lfs"}, description = "LFS server url (can be determinated by --git paramter)", required = false)
    @Nullable
    private String lfs;
    @Parameter(names = {"-t", "--write-threads"}, description = "IO thread count", required = false)
    private int writeThreads = 2;
    @Parameter(names = {"-u", "--upload-threads"}, description = "HTTP upload thread count", required = false)
    private int uploadThreads = 4;
    @Parameter(names = {"--check-lfs"}, description = "Check LFS server settings and exit")
    private boolean checkLfs = false;

    @Parameter(description = "LFS file suffixes")
    @NotNull
    private List<String> suffixes = new ArrayList<>();
    @Parameter(names = {"-h", "--help"}, description = "Show help", help = true)
    private boolean help = false;
  }

}
