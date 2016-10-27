package git.path.matcher.name;

import git.path.NameMatcher;
import org.jetbrains.annotations.NotNull;

/**
 * Recursive directory matcher like "**".
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class RecursiveMatcher implements NameMatcher {
  public static final RecursiveMatcher INSTANCE = new RecursiveMatcher();

  private RecursiveMatcher() {
  }

  @Override
  public boolean isMatch(@NotNull String name, boolean isDir) {
    return true;
  }

  @Override
  public boolean isRecursive() {
    return true;
  }

  @Override
  @NotNull
  public String toString() {
    return "**/";
  }
}
