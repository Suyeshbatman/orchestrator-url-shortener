package com.acme.orchestrator.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.Effect;
import com.acme.orchestrator.core.policy.PolicyEngine;
import com.acme.orchestrator.core.policy.ProposedAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceAndFilesTest {

  private static final PolicyEngine POLICIES = PolicyEngine.load(Path.of("..", "config"));
  private static final String LINK = "shortener-service/src/main/java/com/acme/shortener/Link.java";
  private static final String QR = "shortener-service/src/main/java/com/acme/shortener/Qr.java";

  @TempDir Path tmp;
  private Path source;
  private WorkspaceManager manager;
  private Workspace workspace;
  private final List<ProposedAction> asked = new ArrayList<>();
  private FileTool files;

  @BeforeEach
  void setUp() throws Exception {
    source = TempRepo.create(tmp);
    manager = new WorkspaceManager(source, tmp.resolve("worktrees"), "main");
    workspace = manager.create("run-1");
    files =
        new FileTool(
            workspace,
            action -> {
              asked.add(action);
              return POLICIES.evaluate(action, AutonomyLevel.L1_SUPERVISED);
            });
  }

  @Test
  void writesOfUnreviewedModelOutputAreMarkedForThePolicies() {
    List<ProposedAction> seen = new java.util.ArrayList<>();
    FileTool live =
        new FileTool(
            workspace,
            action -> {
              seen.add(action);
              return POLICIES.evaluate(action, AutonomyLevel.L1_SUPERVISED);
            },
            true);

    live.write("shortener-service/src/main/java/com/acme/shortener/Live.java", "class Live {}\n");
    files.write("shortener-service/src/main/java/com/acme/shortener/Fixture.java", "class F {}\n");

    assertThat(seen)
        .singleElement()
        .isEqualTo(
            new ProposedAction.FileWrite(
                "shortener-service/src/main/java/com/acme/shortener/Live.java",
                "class Live {}\n",
                true));
    assertThat(asked.getLast())
        .isEqualTo(
            new ProposedAction.FileWrite(
                "shortener-service/src/main/java/com/acme/shortener/Fixture.java", "class F {}\n"));
  }

  // ---- workspace ------------------------------------------------------------------------------

  @Test
  void eachRunGetsAPrivateCloneOnItsOwnBranch() throws Exception {
    assertThat(workspace.root())
        .isEqualTo(tmp.resolve("worktrees").resolve("run-1").toAbsolutePath());
    assertThat(workspace.branch()).isEqualTo("agent/run-1");
    try (Git ws = Git.open(workspace.root().toFile());
        Git src = Git.open(source.toFile())) {
      assertThat(ws.getRepository().getBranch()).isEqualTo("agent/run-1");
      assertThat(workspace.baseCommit())
          .isEqualTo(src.getRepository().resolve("refs/heads/main").name());
    }

    files.write(QR, "class Qr {}\n");
    assertThat(source.resolve(QR)).doesNotExist(); // the source repository is untouched

    assertThat(manager.create("run-1")).isEqualTo(workspace); // resuming opens the same one
    assertThat(manager.open("run-2")).isEmpty();
    assertThatThrownBy(() -> manager.create("../escape"))
        .isInstanceOf(IllegalArgumentException.class);

    manager.delete("run-1");
    assertThat(workspace.root()).doesNotExist();
  }

  // ---- reading --------------------------------------------------------------------------------

  @Test
  void filesCanBeReadListedAndSearched() {
    assertThat(files.read(LINK)).contains("record Link");
    assertThat(files.exists("README.md")).isTrue();
    assertThat(files.list("shortener-service"))
        .containsExactly(LINK, "shortener-service/src/main/resources/db/migration/V1__init.sql");
    assertThat(files.list(""))
        .contains("README.md", ".gitignore")
        .noneMatch(p -> p.startsWith(".git/"));
    assertThat(files.search("", "record \\w+"))
        .containsExactly(new FileTool.Match(LINK, 3, "public record Link(String code) {}"));
  }

  // ---- writing --------------------------------------------------------------------------------

  @Test
  void writesAreCheckedByThePolicyEngineFirst() throws Exception {
    Decision allowed = files.write(QR, "class Qr {}\n");
    assertThat(allowed.effect()).isEqualTo(Effect.ALLOW);
    assertThat(Files.readString(workspace.root().resolve(QR))).isEqualTo("class Qr {}\n");
    assertThat(asked).containsExactly(new ProposedAction.FileWrite(QR, "class Qr {}\n"));

    // REQUIRE_APPROVAL: written on the run's branch; the stage will wait for a human
    Decision migration =
        files.write(
            "shortener-service/src/main/resources/db/migration/V2__qr.sql", "alter table link;\n");
    assertThat(migration.effect()).isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(files.exists("shortener-service/src/main/resources/db/migration/V2__qr.sql"))
        .isTrue();
  }

  @Test
  void aDeniedWriteWritesNothing() {
    assertThatThrownBy(() -> files.write("config/policies.yml", "policies: []\n"))
        .isInstanceOf(PolicyDeniedException.class)
        .isInstanceOf(IllegalStateException.class) // fatal for the engine
        .hasMessageContaining("writing config/policies.yml not allowed (DENY)");
    assertThat(workspace.root().resolve("config/policies.yml")).doesNotExist();

    assertThatThrownBy(() -> files.delete("README.md")).isInstanceOf(PolicyDeniedException.class);
    assertThat(files.exists("README.md")).isTrue();
  }

  @Test
  void deletesAreCheckedLikeWrites() {
    assertThat(files.delete(LINK).effect()).isEqualTo(Effect.ALLOW);
    assertThat(files.exists(LINK)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../outside.txt",
        "shortener-service/../../outside.txt",
        "/etc/passwd",
        "C:\\Windows\\win.ini",
        "~/.ssh/id_rsa",
        ".git/config",
        ".GIT/hooks/pre-commit",
        ""
      })
  void pathsOutsideTheWorkspaceAreBlockedBeforeThePolicyIsAsked(String path) {
    assertThatThrownBy(() -> files.write(path, "x")).isInstanceOf(PathEscapeException.class);
    assertThatThrownBy(() -> files.read(path)).isInstanceOf(PathEscapeException.class);
    assertThat(asked).isEmpty();
    assertThat(tmp.resolve("outside.txt")).doesNotExist();
  }

  @Test
  void aSymlinkLeadingOutsideIsBlocked() throws Exception {
    Path outside = Files.createDirectories(tmp.resolve("secrets"));
    Files.writeString(outside.resolve("key.txt"), "secret");
    Path link = workspace.root().resolve("shortener-service/src/link");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (Exception e) {
      assumeTrue(false, "symlinks need extra rights on this machine: " + e);
    }

    assertThatThrownBy(() -> files.read("shortener-service/src/link/key.txt"))
        .isInstanceOf(PathEscapeException.class)
        .hasMessageContaining("via a link");
    assertThatThrownBy(() -> files.write("shortener-service/src/link/new.txt", "x"))
        .isInstanceOf(PathEscapeException.class);
    assertThat(outside.resolve("new.txt")).doesNotExist();
  }

  // ---- diff -----------------------------------------------------------------------------------

  @Test
  void theDiffCoversEditsNewFilesAndDeletionsButNotIgnoredFiles() throws Exception {
    files.write(
        LINK, "package com.acme.shortener;\n\npublic record Link(String code, long hits) {}\n");
    files.write(QR, "class Qr {}\n");
    Files.delete(workspace.root().resolve("README.md")); // even deletions made behind the tool
    Files.createDirectories(workspace.root().resolve("target"));
    Files.writeString(workspace.root().resolve("target/build.log"), "noise");

    DiffTool.Result diff = new DiffTool(workspace).diff();

    assertThat(diff.paths()).containsExactlyInAnyOrder(LINK, QR, "README.md");
    assertThat(diff.filesChanged()).isEqualTo(3);
    assertThat(diff.linesChanged()).isEqualTo(4); // 1 removed + 1 added, 1 added, 1 removed
    assertThat(diff.unifiedDiff())
        .contains("+++ b/" + QR)
        .contains("-public record Link(String code) {}")
        .contains("+public record Link(String code, long hits) {}")
        .contains("+++ /dev/null")
        .doesNotContain("build.log");

    // the policy engine reads the same diff: the deleted README is outside the path jail
    Decision decision = POLICIES.evaluate(diff.toAction(), AutonomyLevel.L1_SUPERVISED);
    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(decision.matchedPolicies().getFirst().reason()).contains("README.md");
  }

  @Test
  void anUntouchedWorkspaceHasAnEmptyDiff() {
    assertThat(new DiffTool(workspace).diff().isEmpty()).isTrue();
  }

  // ---- checkpoints ----------------------------------------------------------------------------

  @Test
  void aCheckpointRestoresTheWorkspaceExactly() throws Exception {
    GitCheckpointManager checkpoints =
        new GitCheckpointManager(
            runId -> workspace, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    files.write(QR, "class Qr {}\n");
    var before = checkpoints.create("run-1", "implementation");

    files.write(QR, "class Qr { broken");
    files.write(
        "shortener-service/src/main/java/com/acme/shortener/Extra.java", "class Extra {}\n");
    files.delete(LINK);
    checkpoints.restore(before);

    assertThat(files.read(QR)).isEqualTo("class Qr {}\n");
    assertThat(files.exists("shortener-service/src/main/java/com/acme/shortener/Extra.java"))
        .isFalse();
    assertThat(files.read(LINK)).contains("record Link");
    try (Git git = Git.open(workspace.root().toFile())) {
      assertThat(git.getRepository().resolve("refs/orch/run-1/implementation").name())
          .isEqualTo(before.id());
      assertThat(git.getRepository().getBranch()).isEqualTo("agent/run-1");
    }
    // the checkpoint commit is part of the run's change, so it shows in the diff
    assertThat(new DiffTool(workspace).diff().paths()).containsExactly(QR);

    assertThatThrownBy(() ->
            checkpoints.restore(
                new com.acme.orchestrator.core.checkpoint.CheckpointRef(
                    "0123456789abcdef0123456789abcdef01234567", "run-1", "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void restoringSomePathsLeavesTheRestOfTheWorkspaceAlone() throws Exception {
    GitCheckpointManager checkpoints =
        new GitCheckpointManager(runId -> workspace, Clock.systemUTC());
    String extra = "shortener-service/src/main/java/com/acme/shortener/Extra.java";
    files.write(QR, "class Qr {}\n");
    var before = checkpoints.create("run-1", "implementation.T1");

    files.write(QR, "class Qr { mine");
    files.write(extra, "class Extra {}\n"); // new since the checkpoint
    files.write(LINK, "record Link(String sibling) {}\n"); // another task's work
    checkpoints.restorePaths(before, List.of(QR, extra));

    assertThat(files.read(QR)).isEqualTo("class Qr {}\n");
    assertThat(files.exists(extra)).isFalse();
    assertThat(files.read(LINK)).isEqualTo("record Link(String sibling) {}\n");
    assertThatThrownBy(() -> checkpoints.restorePaths(before, List.of("../outside.txt")))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void checkpointsWorkWithNothingChangedAndRepeatedLabels() {
    GitCheckpointManager checkpoints =
        new GitCheckpointManager(runId -> workspace, Clock.systemUTC());

    var first = checkpoints.create("run-1", "design");
    var second = checkpoints.create("run-1", "design"); // a loop: same label, new checkpoint

    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(GitCheckpointManager.refName("run-1", "safe stop"))
        .isEqualTo("refs/orch/run-1/safe_stop");
  }
}
