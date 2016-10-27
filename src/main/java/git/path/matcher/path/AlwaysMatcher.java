package git.path.matcher.path;

import git.path.PathMatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Matches with any path.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class AlwaysMatcher implements PathMatcher {
  @NotNull
  public final static AlwaysMatcher INSTANCE = new AlwaysMatcher();

  private AlwaysMatcher() {
  }

  @Nullable
  @Override
  public PathMatcher createChild(@NotNull String name, boolean isDir) {
    return this;
  }

  @Override
  public boolean isMatch() {
    return true;
  }
}
