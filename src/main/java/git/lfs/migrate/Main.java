package git.lfs.migrate;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

  public static void processRepository(@NotNull File repository, @NotNull String... masks) {

  }

  public static class CmdArgs {
    @Parameter(names = {"-r", "--repo"}, description = "Repository", required = true)
    @NotNull
    private File repository;

    @Parameter(description = "LFS file masks")
    private List<String> masks = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, description = "Show help", help = true)
    private boolean help = false;
  }

}
