package git.lfs.migrate;

import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Key of converter task.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public final class TaskKey {

  @NotNull
  private final GitConverter.TaskType type;
  @NotNull
  private final ObjectId objectId;
  @Nullable
  private final String path;

  public TaskKey(@NotNull GitConverter.TaskType type, @Nullable String path, @NotNull ObjectId objectId) {
    this.type = type;
    this.path = path;
    this.objectId = objectId.copy();
    if (type.needPath() == (path == null)) {
      throw new IllegalStateException();
    }
  }

  @NotNull
  public GitConverter.TaskType getType() {
    return type;
  }

  @NotNull
  public ObjectId getObjectId() {
    return objectId;
  }

  @Nullable
  public String getPath() {
    return path;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    TaskKey taskKey = (TaskKey) o;

    return (type == taskKey.type)
        && objectId.equals(taskKey.objectId)
        && Objects.equals(path, taskKey.path);
  }

  @Override
  public int hashCode() {
    int result = type.hashCode();
    result = 31 * result + objectId.hashCode();
    if (path != null) {
      result = 31 * result + path.hashCode();
    }
    return result;
  }

  @Override
  public String toString() {
    return type + ":" + objectId.name() + (path == null ? "" : " (" + path + ")");
  }
}
