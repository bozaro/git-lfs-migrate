package git.lfs.migrate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.UndirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.graph.SimpleGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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
    processRepository(cmd.repository, cmd.masks.toArray(new String[cmd.masks.size()]));
  }

  public static void processRepository(@NotNull File repositoryPath, @NotNull String... masks) throws IOException {
    Repository repository = new FileRepositoryBuilder()
        .setMustExist(true)
        .setGitDir(repositoryPath).build();
    try {
      // Load all revision list.
      log.info("Reading full revisions list...");
      final List<ObjectId> revisions = loadCommitList(repository);
      log.info("Found revisions: {}", revisions.size());
      int i = 0;
      for (ObjectId revision: revisions) {
        i += 1;
        log.info("  convert revision: {} ({}/{})", revision.getName(), i, revisions.size());

      }
    } finally {
      repository.close();
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
    @Parameter(names = {"-r", "--repo"}, description = "Repository", required = true)
    @NotNull
    private File repository;

    @Parameter(description = "LFS file masks")
    @NotNull
    private List<String> masks = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, description = "Show help", help = true)
    private boolean help = false;
  }

}
