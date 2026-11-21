package com.acme.orchestrator.agents.tools;

import com.acme.orchestrator.core.checkpoint.CheckpointManager;
import com.acme.orchestrator.core.checkpoint.CheckpointRef;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * The core's checkpoint port on Git: a checkpoint is a commit of everything in the workspace (even
 * when nothing changed) on the agent branch, also kept as {@code refs/orch/<runId>/<label>}.
 * Restoring is a hard reset to that commit plus removing untracked files, so the workspace is
 * exactly as it was. The checkpoint id is the commit id.
 */
public final class GitCheckpointManager implements CheckpointManager {

  private static final PersonIdent ORCHESTRATOR =
      new PersonIdent("orchestrator", "orchestrator@localhost");

  private final Function<String, Workspace> workspaces;
  private final Clock clock;

  /**
   * @param workspaces the workspace of a run id (e.g. {@code WorkspaceManager::create})
   * @param clock stamps the checkpoint commits
   */
  public GitCheckpointManager(Function<String, Workspace> workspaces, Clock clock) {
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public CheckpointRef create(String runId, String label) {
    Workspace workspace = workspaces.apply(runId);
    try (Git git = Git.open(workspace.root().toFile())) {
      git.add().addFilepattern(".").call();
      git.add().setUpdate(true).addFilepattern(".").call(); // deletions too
      PersonIdent who = new PersonIdent(ORCHESTRATOR, clock.instant());
      RevCommit commit =
          git.commit()
              .setMessage("checkpoint: " + label + " (run " + runId + ")")
              .setAuthor(who)
              .setCommitter(who)
              .setAllowEmpty(true)
              .setSign(false)
              .call();
      RefUpdate ref = git.getRepository().updateRef(refName(runId, label));
      ref.setNewObjectId(commit.getId());
      ref.setForceUpdate(true); // a stage that loops gets a new checkpoint under the same label
      RefUpdate.Result result = ref.update();
      if (result != RefUpdate.Result.NEW
          && result != RefUpdate.Result.FORCED
          && result != RefUpdate.Result.NO_CHANGE) {
        throw new IllegalStateException("cannot update " + refName(runId, label) + ": " + result);
      }
      return new CheckpointRef(commit.getId().name(), runId, label);
    } catch (GitAPIException | IOException e) {
      throw new IllegalStateException("cannot create checkpoint " + label + " for run " + runId, e);
    }
  }

  @Override
  public void restore(CheckpointRef ref) {
    Workspace workspace = workspaces.apply(ref.runId());
    try (Git git = Git.open(workspace.root().toFile())) {
      if (!ObjectId.isId(ref.id())
          || !git.getRepository().getObjectDatabase().has(ObjectId.fromString(ref.id()))) {
        throw new IllegalArgumentException("unknown checkpoint " + ref.id());
      }
      ObjectId commit = ObjectId.fromString(ref.id());
      git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit.name()).call();
      git.clean().setCleanDirectories(true).call(); // keeps ignored files such as target/
    } catch (GitAPIException | IOException e) {
      throw new IllegalStateException("cannot restore checkpoint " + ref.id(), e);
    }
  }

  static String refName(String runId, String label) {
    return "refs/orch/" + runId + "/" + label.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
