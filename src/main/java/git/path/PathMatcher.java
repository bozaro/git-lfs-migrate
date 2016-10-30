package git.path;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for path matching.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public interface PathMatcher {
  @Nullable
  PathMatcher createChild(@NotNull String name, boolean isDir);

  boolean isMatch();
}
