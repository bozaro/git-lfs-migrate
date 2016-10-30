package git.path;

import git.path.matcher.name.ComplexMatcher;
import git.path.matcher.name.EqualsMatcher;
import git.path.matcher.name.RecursiveMatcher;
import git.path.matcher.name.SimpleMatcher;
import git.path.matcher.path.AlwaysMatcher;
import git.path.matcher.path.RecursivePathMatcher;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Git wildcard mask.
 * <p>
 * Pattern format: http://git-scm.com/docs/gitignore
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class WildcardHelper {
  public static final char PATH_SEPARATOR = '/';

  @Nullable
  public static PathMatcher createMatcher(@NotNull String pattern, boolean exact) throws InvalidPatternException {
    final NameMatcher[] nameMatchers = createNameMatchers(pattern);
    if (nameMatchers.length > 0) {
      return new RecursivePathMatcher(nameMatchers, exact);
    } else {
      return exact ? null : AlwaysMatcher.INSTANCE;
    }
  }

  private static NameMatcher[] createNameMatchers(@NotNull String pattern) throws InvalidPatternException {
    final List<String> tokens = WildcardHelper.splitPattern(pattern);
    WildcardHelper.normalizePattern(tokens);
    final NameMatcher[] result = new NameMatcher[tokens.size() - 1];
    for (int i = 0; i < result.length; ++i) {
      result[i] = WildcardHelper.nameMatcher(tokens.get(i + 1));
    }
    return result;
  }

  @NotNull
  private static NameMatcher nameMatcher(@NotNull String mask) throws InvalidPatternException {
    if (mask.equals("**/")) {
      return RecursiveMatcher.INSTANCE;
    }
    final boolean dirOnly = mask.endsWith("/");
    final String nameMask = tryRemoveBackslashes(dirOnly ? mask.substring(0, mask.length() - 1) : mask);
    if ((nameMask.indexOf('[') < 0) && (nameMask.indexOf(']') < 0) && (nameMask.indexOf('\\') < 0)) {
      // Subversion compatible mask.
      if (nameMask.indexOf('?') < 0) {
        int asterisk = nameMask.indexOf('*');
        if (asterisk < 0) {
          return new EqualsMatcher(nameMask, dirOnly);
        } else if (mask.indexOf('*', asterisk + 1) < 0) {
          return new SimpleMatcher(nameMask.substring(0, asterisk), nameMask.substring(asterisk + 1), dirOnly);
        }
      }
      return new ComplexMatcher(nameMask, dirOnly, true);
    } else {
      return new ComplexMatcher(nameMask, dirOnly, false);
    }
  }

  @NotNull
  static String tryRemoveBackslashes(@NotNull String pattern) {
    final StringBuilder result = new StringBuilder(pattern.length());
    int start = 0;
    while (true) {
      int next = pattern.indexOf('\\', start);
      if (next == -1) {
        if (start < pattern.length()) {
          result.append(pattern, start, pattern.length());
        }
        break;
      }
      if (next == pattern.length() - 1) {
        // Return original string.
        return pattern;
      }
      switch (pattern.charAt(next + 1)) {
        case ' ':
        case '#':
        case '!':
          result.append(pattern, start, next);
          start = next + 1;
          break;
        default:
          return pattern;
      }
    }
    return result.toString();
  }

  /**
   * Split pattern with saving slashes.
   *
   * @param pattern Path pattern.
   * @return Path pattern items.
   */
  @NotNull
  public static List<String> splitPattern(@NotNull String pattern) {
    final List<String> result = new ArrayList<>(count(pattern, PATH_SEPARATOR) + 1);
    int start = 0;
    while (true) {
      int next = pattern.indexOf(PATH_SEPARATOR, start);
      if (next == -1) {
        if (start < pattern.length()) {
          result.add(pattern.substring(start));
        }
        break;
      }
      result.add(pattern.substring(start, next + 1));
      start = next + 1;
    }
    return result;
  }

  /**
   * Remove redundant pattern parts and make patterns more simple.
   *
   * @param tokens Original modifiable list.
   * @return Return tokens,
   */
  @NotNull
  public static List<String> normalizePattern(@NotNull List<String> tokens) {
    // By default without slashes using mask for files in all subdirectories
    if ((tokens.size() == 1) && !tokens.get(0).startsWith("/")) {
      tokens.add(0, "**/");
    }
    // Normalized pattern always starts with "/"
    if (tokens.size() == 0 || !tokens.get(0).equals("/")) {
      tokens.add(0, "/");
    }
    // Replace:
    //  * "**/*/" to "*/**/"
    //  * "**/**/" to "**/"
    //  * "**.foo" to "**/*.foo"
    int index = 1;
    while (index < tokens.size()) {
      final String thisToken = tokens.get(index);
      final String prevToken = tokens.get(index - 1);
      if (thisToken.equals("/")) {
        tokens.remove(index);
        continue;
      }
      if (thisToken.equals("**/") && prevToken.equals("**/")) {
        tokens.remove(index);
        continue;
      }
      if ((!thisToken.equals("**/")) && thisToken.startsWith("**")) {
        tokens.add(index, "**/");
        tokens.set(index + 1, thisToken.substring(1));
        continue;
      }
      if (thisToken.equals("*/") && prevToken.equals("**/")) {
        tokens.set(index - 1, "*/");
        tokens.set(index, "**/");
        index--;
        continue;
      }
      index++;
    }
    return tokens;
  }

  private static int count(@NotNull String s, char c) {
    int start = 0;
    int count = 0;
    while (true) {
      start = s.indexOf(c, start);
      if (start == -1)
        break;
      count++;
      start++;
    }
    return count;
  }

  public static boolean isMatch(@Nullable PathMatcher matcher, @NotNull String fileName) {
    List<String> items = splitPattern(fileName.substring(1));
    PathMatcher m = matcher;
    for (String item : items) {
      if (m == null) break;
      final boolean dir = item.endsWith(String.valueOf(PATH_SEPARATOR));
      m = m.createChild(dir ? item.substring(0, item.length() - 1) : item, dir);
    }
    return (m != null) && m.isMatch();
  }
}
