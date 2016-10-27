package git.path;

import org.jetbrains.annotations.NotNull;

/**
 * Interface for matching name of path.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public interface NameMatcher {
  boolean isMatch(@NotNull String name, boolean isDir);

  boolean isRecursive();
}
