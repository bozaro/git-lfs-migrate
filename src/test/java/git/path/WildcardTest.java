package git.path;

import com.google.common.io.ByteStreams;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test wildcard parsing.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class WildcardTest {
  @DataProvider
  public static Object[][] splitPatternData() {
    return new Object[][]{
        new Object[]{"foo", new String[]{"foo"}},
        new Object[]{"foo/", new String[]{"foo/"}},
        new Object[]{"/bar", new String[]{"/", "bar"}},
        new Object[]{"/foo/bar/**", new String[]{"/", "foo/", "bar/", "**"}},
    };
  }

  @Test(dataProvider = "splitPatternData")
  public static void splitPatternTest(@NotNull String pattern, @NotNull String[] expected) {
    final List<String> actual = WildcardHelper.splitPattern(pattern);
    Assert.assertEquals(actual.toArray(new String[actual.size()]), expected, pattern);
  }

  @DataProvider
  public static Object[][] normalizePatternData() {
    return new Object[][]{
        // Simple mask
        new Object[]{"/", new String[0]},
        new Object[]{"*/", new String[]{"*/", "**/"}},
        new Object[]{"*", new String[]{"**/", "*"}},
        new Object[]{"**", new String[]{"**/", "*"}},
        new Object[]{"**/", new String[]{"**/"}},
        new Object[]{"foo", new String[]{"**/", "foo"}},
        new Object[]{"foo/", new String[]{"**/", "foo/"}},
        new Object[]{"/foo", new String[]{"foo"}},

        // Convert path file mask
        new Object[]{"foo/**.bar", new String[]{"foo/", "**/", "*.bar"}},
        new Object[]{"foo/***.bar", new String[]{"foo/", "**/", "*.bar"}},

        // Collapse and reorder adjacent masks
        new Object[]{"foo/*/bar", new String[]{"foo/", "*/", "bar"}},
        new Object[]{"foo/**/bar", new String[]{"foo/", "**/", "bar"}},
        new Object[]{"foo/*/*/bar", new String[]{"foo/", "*/", "*/", "bar"}},
        new Object[]{"foo/**/*/bar", new String[]{"foo/", "*/", "**/", "bar"}},
        new Object[]{"foo/*/**/bar", new String[]{"foo/", "*/", "**/", "bar"}},
        new Object[]{"foo/*/**.bar", new String[]{"foo/", "*/", "**/", "*.bar"}},
        new Object[]{"foo/**/**/bar", new String[]{"foo/", "**/", "bar"}},
        new Object[]{"foo/**/**.bar", new String[]{"foo/", "**/", "*.bar"}},
        new Object[]{"foo/**/*/**/*/bar", new String[]{"foo/", "*/", "*/", "**/", "bar"}},
        new Object[]{"foo/**/*/**/*/**.bar", new String[]{"foo/", "*/", "*/", "**/", "*.bar"}},

        // Collapse trailing masks
        new Object[]{"foo/**", new String[]{"foo/", "**/", "*"}},
        new Object[]{"foo/**/*", new String[]{"foo/", "**/", "*"}},
        new Object[]{"foo/**/*/*", new String[]{"foo/", "*/", "**/", "*"}},
        new Object[]{"foo/**/", new String[]{"foo/", "**/"}},
        new Object[]{"foo/**/*/", new String[]{"foo/", "*/", "**/"}},
        new Object[]{"foo/**/*/*/", new String[]{"foo/", "*/", "*/", "**/"}},
    };
  }

  @Test(dataProvider = "normalizePatternData")
  public static void normalizePatternTest(@NotNull String pattern, @NotNull String[] expected) {
    final List<String> actual = WildcardHelper.normalizePattern(WildcardHelper.splitPattern(pattern));
    Assert.assertTrue(actual.size() > 0);
    Assert.assertEquals(actual.remove(0), "/");
    Assert.assertEquals(actual.toArray(new String[actual.size()]), expected, pattern);
  }

  @DataProvider
  public static Object[][] pathMatcherData() {
    return new Object[][]{
        // Simple pattern
        new Object[]{"/", "foo/bar", true, null},
        new Object[]{"*", "foo/bar", true, true},
        new Object[]{"*/", "foo/bar", true, null},
        new Object[]{"/", "foo/bar/", true, null},
        new Object[]{"*", "foo/bar/", true, true},
        new Object[]{"*/", "foo/bar/", true, true},
        new Object[]{"**/", "foo/bar/", true, true},
        new Object[]{"foo/**/", "foo/bar/", true, true},
        new Object[]{"foo/**/", "foo/bar/xxx", true, null},
        new Object[]{"foo/**/", "foo/bar/xxx/", true, true},
        new Object[]{"f*o", "foo/bar", true, null},
        new Object[]{"/f*o", "foo/bar", true, null},
        new Object[]{"f*o/", "foo/bar", true, null},
        new Object[]{"foo/", "foo/bar", true, null},
        new Object[]{"/foo/", "foo/bar", true, null},
        new Object[]{"/foo", "foo/", true, true},
        new Object[]{"foo", "foo/", true, true},
        new Object[]{"foo/", "foo/", true, true},
        new Object[]{"foo/", "foo", null, null},
        new Object[]{"bar", "foo/bar", true, true},
        new Object[]{"b*r", "foo/bar", true, true},
        new Object[]{"/bar", "foo/bar", null, null},
        new Object[]{"bar/", "foo/bar", null, null},
        new Object[]{"b*r/", "foo/bar", null, null},
        new Object[]{"bar/", "foo/bar/", true, true},
        new Object[]{"b*r/", "foo/bar/", true, true},
        new Object[]{"b[a-z]r", "foo/bar", true, true},
        new Object[]{"b[a-z]r", "foo/b0r", null, null},
        new Object[]{"b[a-z]r", "foo/b0r/", false, false},
        new Object[]{"/t*e*t", "test", true, true},
        // More complex pattern
        new Object[]{"foo/*/bar/", "foo/bar/", false, false},
        new Object[]{"foo/*/bar/", "bar/", null, null},
        new Object[]{"foo/*/bar/", "foo/a/bar/", true, true},
        new Object[]{"foo/*/bar/", "foo/a/b/bar/", null, null},
        new Object[]{"foo/*/*/bar/", "foo/a/b/bar/", true, true},

        new Object[]{"foo/**/bar/a/", "foo/bar/b/bar/a/", true, true},
        new Object[]{"foo/**/bar/a/", "foo/bar/bar/bar/a/", true, true},
        new Object[]{"foo/**/bar/a/", "foo/bar/bar/b/a/", false, false},
        new Object[]{"foo/**/bar/", "foo/bar/", true, true},
        new Object[]{"foo/**/bar/", "bar/", null, null},
        new Object[]{"foo/**/bar/", "foo/a/bar/", true, true},
        new Object[]{"foo/**/bar/", "foo/a/b/bar/", true, true},
        new Object[]{"foo/*/**/*/bar/", "foo/a/bar/", false, false},
        new Object[]{"foo/*/**/*/bar/", "foo/a/b/bar/", true, true},
        new Object[]{"foo/*/**/*/bar/", "foo/a/b/c/bar/", true, true},
        new Object[]{"foo/**/xxx/**/bar/", "foo/xxx/bar/", true, true},
        new Object[]{"foo/**/xxx/**/bar/", "foo/xxx/b/c/bar/", true, true},
        new Object[]{"foo/**/xxx/**/bar/", "foo/a/xxx/c/bar/", true, true},
        new Object[]{"foo/**/xxx/**/bar/", "foo/a/c/xxx/bar/", true, true},
        new Object[]{"foo/**/xxx/**/bar/", "foo/bar/xxx/", false, false},
        new Object[]{"foo/**/xxx/**/bar/", "foo/bar/xxx/bar/", true, true},
        new Object[]{"foo/**/xxx/**/bar/", "foo/bar/xxx/xxx/bar/", true, true},
    };
  }

  @Test(dataProvider = "pathMatcherData")
  public static void nativeMatcherExactTest(@NotNull String pattern, @NotNull String path, @Nullable Boolean ignored, @Nullable Boolean expectedMatch) throws InvalidPatternException, IOException, InterruptedException {
    Path temp = Files.createTempDirectory("git-matcher");
    try {
      if (new ProcessBuilder()
          .directory(temp.toFile())
          .command("git", "init", ".")
          .start()
          .waitFor() != 0) {
        throw new SkipException("Can't find git");
      }
      Files.write(temp.resolve(".gitattributes"), (pattern + " test\n").getBytes(StandardCharsets.UTF_8));
      byte[] output = ByteStreams.toByteArray(
          new ProcessBuilder()
              .directory(temp.toFile())
              .command("git", "check-attr", "-a", "--", path)
              .start()
              .getInputStream()
      );
      Assert.assertEquals(output.length > 0, expectedMatch == Boolean.TRUE);
    } finally {
      Files.walkFileTree(temp, new DeleteTreeVisitor());
    }
  }

  @Test(dataProvider = "pathMatcherData")
  public static void pathMatcherPrefixTest(@NotNull String pattern, @NotNull String path, @Nullable Boolean expectedMatch, @Nullable Boolean ignored) throws InvalidPatternException {
    pathMatcherCheck(pattern, path, false, expectedMatch);
  }

  @Test(dataProvider = "pathMatcherData")
  public static void pathMatcherExactTest(@NotNull String pattern, @NotNull String path, @Nullable Boolean ignored, @Nullable Boolean expectedMatch) throws InvalidPatternException {
    pathMatcherCheck(pattern, path, true, expectedMatch);
  }

  private static void pathMatcherCheck(@NotNull String pattern, @NotNull String path, boolean exact, @Nullable Boolean expectedMatch) throws InvalidPatternException {
    PathMatcher matcher = WildcardHelper.createMatcher(pattern, exact);
    for (String name : WildcardHelper.splitPattern(path)) {
      if (matcher == null) break;
      boolean isDir = name.endsWith("/");
      matcher = matcher.createChild(isDir ? name.substring(0, name.length() - 1) : name, isDir);
    }
    if (expectedMatch == null) {
      Assert.assertNull(matcher);
    } else {
      Assert.assertNotNull(matcher);
      Assert.assertEquals(matcher.isMatch(), expectedMatch.booleanValue());
    }
  }

  @DataProvider
  public static Object[][] tryRemoveBackslashesData() {
    return new Object[][]{
        new Object[]{"test", "test"},
        new Object[]{"test\\n", "test\\n"},
        new Object[]{"space\\ ", "space "},
        new Object[]{"foo\\!bar\\ ", "foo!bar "},
        new Object[]{"\\#some", "#some"},
        new Object[]{"foo\\[bar", "foo\\[bar"},
    };
  }

  @Test(dataProvider = "tryRemoveBackslashesData")
  public static void tryRemoveBackslashesTest(@NotNull String pattern, @NotNull String expected) {
    Assert.assertEquals(WildcardHelper.tryRemoveBackslashes(pattern), expected);
  }
}
