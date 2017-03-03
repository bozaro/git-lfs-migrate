package git.lfs.migrate;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.jetbrains.annotations.NotNull;
import org.mapdb.DBMaker;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileSystem;

/**
 * Full LFS convert.
 *
 * @author Artem V. Navrotskiy
 */
public class GitConverterTest {
  @DataProvider
  public Object[][] matchFilenameProvider() {
    return new Object[][]{
        new Object[]{"/LICENSE", true},
        new Object[]{"/foo/bar/LICENSE", true},
        new Object[]{"/LICENSE/foo/bar", false},
        new Object[]{"/foo/LICENSE/bar", false},
        new Object[]{"/dist.zip", true},
        new Object[]{"/foo/bar/dist.zip", true},
        new Object[]{"/dist.zip/foo/bar", false},
        new Object[]{"/foo/dist.zip/bar", false},
        new Object[]{"/.some", true},
        new Object[]{"/foo/bar/.some", true},
        new Object[]{"/.some/foo/bar", false},
        new Object[]{"/foo/.some/bar", false},
        new Object[]{"/test_some", true},
        new Object[]{"/foo/bar/test_some", true},
        new Object[]{"/test_some/foo/bar", false},
        new Object[]{"/root", true},
        new Object[]{"/root/data", false},
        new Object[]{"/some/data", true},
        new Object[]{"/some/data/data", false},
        new Object[]{"/qwerty/some/data", false},
    };
  }

  @Test(dataProvider = "matchFilenameProvider")
  public void matchFilenameTest(@NotNull String path, boolean expected) throws IOException, InvalidPatternException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    GitConverter converter = new GitConverter(DBMaker.memoryDB().make(), fs.getPath("/tmp/migrate"), null, new String[]{
        "*.zip",
        ".*",
        "LICENSE",
        "test*",
        "/root",
        "some/data",
    });
    Assert.assertEquals(converter.matchFilename(path), expected);
  }
}
