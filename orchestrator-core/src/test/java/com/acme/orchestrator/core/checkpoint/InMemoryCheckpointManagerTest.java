package com.acme.orchestrator.core.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryCheckpointManagerTest {

  @Test
  void restoreRoundTripsTheWorkspace() {
    InMemoryCheckpointManager workspace = new InMemoryCheckpointManager();
    workspace.write("a.txt", "one");
    CheckpointRef before = workspace.create("run-1", "impl");

    workspace.write("a.txt", "two");
    workspace.write("b.txt", "new");
    workspace.restore(before);

    assertThat(workspace.files()).isEqualTo(Map.of("a.txt", "one"));
    assertThat(workspace.restored()).containsExactly(before);
    assertThat(workspace.created()).isEqualTo(1);
    assertThat(before).isEqualTo(new CheckpointRef("cp-1", "run-1", "impl"));
  }

  @Test
  void restoringSomePathsLeavesTheOthersAlone() {
    InMemoryCheckpointManager workspace = new InMemoryCheckpointManager();
    workspace.write("a.txt", "one");
    CheckpointRef before = workspace.create("run-1", "impl.T1");

    workspace.write("a.txt", "two");
    workspace.write("b.txt", "new");
    workspace.write("c.txt", "a sibling's");
    workspace.restorePaths(before, java.util.List.of("a.txt", "b.txt"));

    assertThat(workspace.files()).isEqualTo(Map.of("a.txt", "one", "c.txt", "a sibling's"));
    assertThatThrownBy(
            () -> workspace.restorePaths(new CheckpointRef("cp-9", "r", "x"), java.util.List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    CheckpointManager.NONE.restorePaths(before, java.util.List.of("a.txt")); // nothing to do
  }

  @Test
  void unknownCheckpointsAreRejected() {
    assertThatThrownBy(
            () -> new InMemoryCheckpointManager().restore(new CheckpointRef("cp-9", "r", "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theNoOpManagerRecordsButRestoresNothing() {
    CheckpointRef ref = CheckpointManager.NONE.create("run-1", "impl");

    assertThat(ref.id()).isEqualTo("none");
    CheckpointManager.NONE.restore(ref); // no workspace, nothing to do
  }
}
