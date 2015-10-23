package git.lfs.migrate;

import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;

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

  public TaskKey(@NotNull GitConverter.TaskType type, @NotNull ObjectId objectId) {
    this.type = type;
    this.objectId = objectId.copy();
  }

  @NotNull
  public GitConverter.TaskType getType() {
    return type;
  }

  @NotNull
  public ObjectId getObjectId() {
    return objectId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    TaskKey taskKey = (TaskKey) o;

    return (type == taskKey.type)
        && objectId.equals(taskKey.objectId);

  }

  @Override
  public int hashCode() {
    int result = type.hashCode();
    result = 31 * result + objectId.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return type + ":" + objectId.name();
  }
}
