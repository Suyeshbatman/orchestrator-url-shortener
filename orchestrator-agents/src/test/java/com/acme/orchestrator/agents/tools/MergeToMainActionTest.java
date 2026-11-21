package com.acme.orchestrator.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.policy.PolicyEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeToMainActionTest {

  private static final String QR = "shortener-service/src/main/java/com/acme/shortener/Qr.java";

  @TempDir Path tmp;
  private Path bare;
  private WorkspaceManager manager;
  private Workspace workspace;
  private MergeToMainAction action;
  private StageContext ctx;

  @BeforeEach
  void setUp() throws Exception {
    Path source = TempRepo.create(tmp);
    bare = tmp.resolve("remote.git");
    Git.cloneRepository()
        .setURI(source.toUri().toString())
        .setDirectory(bare.toFile())
        .setBare(true)
        .call()
        .close();
    manager = new WorkspaceManager(bare.toUri().toString(), tmp.resolve("ws"), "main");
    workspace = manager.create("run-1");
    action = new MergeToMainAction(manager::create, "main", Clock.systemUTC());
    Run run =
        Run.create(
            "run-1",
            new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml")),
            RunMode.GREENFIELD,
            AutonomyLevel.L1_SUPERVISED);
    ctx =
        StageContext.standalone(
            run,
            "publish",
            1,
            List.of(),
            new InMemoryArtifactStore(Clock.systemUTC()),
            ToolRegistry.EMPTY,
            PolicyEngine.withoutPolicies());
  }

  /** The exact content of {@code path} on the remote's main (read from the tree, no checkout). */
  private String onRemoteMain(String path) throws Exception {
    try (Git remote = Git.open(bare.toFile());
        RevWalk walk = new RevWalk(remote.getRepository())) {
      RevCommit main = walk.parseCommit(remote.getRepository().resolve("refs/heads/main"));
      try (TreeWalk tree = TreeWalk.forPath(remote.getRepository(), path, main.getTree())) {
        return tree == null
            ? null
            : new String(
                remote.getRepository().open(tree.getObjectId(0)).getBytes(),
                StandardCharsets.UTF_8);
      }
    }
  }

  @Test
  void theRunsBranchIsMergedIntoMainOnTheRemote() throws Exception {
    Files.createDirectories(workspace.root().resolve(QR).getParent());
    Files.writeString(workspace.root().resolve(QR), "class Qr {}\n"); // uncommitted: committed now

    AgentResult result = action.execute(ctx);

    assertThat(onRemoteMain(QR)).isEqualTo("class Qr {}\n");
    AgentResult.Success success = (AgentResult.Success) result;
    assertThat(success.summary()).startsWith("merged agent/run-1 into main at ");
    assertThat(success.artifacts().getFirst().contentJson())
        .contains("\"branch\":\"agent/run-1\"")
        .contains("\"baseBranch\":\"main\"");
    try (Git remote = Git.open(bare.toFile())) {
      assertThat(remote.getRepository().resolve("refs/heads/agent/run-1"))
          .isEqualTo(remote.getRepository().resolve("refs/heads/main"));
    }
  }

  @Test
  void mainMovingOnWithoutConflictsIsMergedIn() throws Exception {
    pushToRemoteMain("docs/other.md", "someone else's change\n");
    Files.createDirectories(workspace.root().resolve(QR).getParent());
    Files.writeString(workspace.root().resolve(QR), "class Qr {}\n");

    action.execute(ctx);

    assertThat(onRemoteMain(QR)).isEqualTo("class Qr {}\n");
    assertThat(onRemoteMain("docs/other.md")).isEqualTo("someone else's change\n");
  }

  @Test
  void aConflictWithMainStopsThePublish() throws Exception {
    pushToRemoteMain("README.md", "# changed on main\n");
    Files.writeString(workspace.root().resolve("README.md"), "# changed by the run\n");

    assertThatThrownBy(() -> action.execute(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("main moved on and conflicts with this run")
        .hasMessageContaining("README.md");
    assertThat(onRemoteMain("README.md")).isEqualTo("# changed on main\n");
  }

  @Test
  void aWorkingCopyIsNeverPublishedInto() throws Exception {
    Path source = TempRepo.create(tmp.resolve("other"));
    WorkspaceManager local = new WorkspaceManager(source, tmp.resolve("ws2"), "main");
    local.create("run-1");

    assertThatThrownBy(
            () -> new MergeToMainAction(local::create, "main", Clock.systemUTC()).execute(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refusing to publish into a working copy");
    assertThatThrownBy(() -> MergeToMainAction.refuseNonBareLocal(null))
        .hasMessageContaining("no origin");
    MergeToMainAction.refuseNonBareLocal(
        "https://github.com/acme/shortener.git"); // remotes are fine
  }

  private void pushToRemoteMain(String path, String content) throws Exception {
    Path other = tmp.resolve("other-dev");
    try (Git git =
        Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(other.toFile()).call()) {
      Files.createDirectories(other.resolve(path).getParent());
      Files.writeString(other.resolve(path), content);
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("elsewhere")
          .setSign(false)
          .setAuthor("dev", "dev@example.com")
          .call();
      git.push().call();
    }
  }
}
