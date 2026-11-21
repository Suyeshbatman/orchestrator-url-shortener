package com.acme.orchestrator.agents.tools;

import com.acme.orchestrator.core.engine.Agent;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.ArtifactOutput;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.engine.TransientAgentException;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;

/**
 * The {@code git.mergeToMain} action: publishes the run's branch. The engine runs it only after a
 * human approved it. It commits what is left in the workspace, merges the latest base branch from
 * the origin into the run's branch (so main can only fast-forward), and pushes that to the origin's
 * base branch, plus the run's branch for the record.
 *
 * <p>The origin must be a bare or remote repository: pushing into someone's checked-out working
 * copy would change files under their feet, so a non-bare local origin is refused.
 */
public final class MergeToMainAction implements Agent {

  public static final String NAME = "git.mergeToMain";

  private static final PersonIdent ORCHESTRATOR =
      new PersonIdent("orchestrator", "orchestrator@localhost");

  private final Function<String, Workspace> workspaces;
  private final String baseBranch;
  private final Clock clock;

  public MergeToMainAction(Function<String, Workspace> workspaces, String baseBranch, Clock clock) {
    this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    this.baseBranch = Objects.requireNonNull(baseBranch, "baseBranch");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public AgentResult execute(StageContext ctx) throws GitAPIException, IOException {
    Workspace workspace = workspaces.apply(ctx.run().id());
    try (Git git = Git.open(workspace.root().toFile())) {
      String remote = git.getRepository().getConfig().getString("remote", "origin", "url");
      refuseNonBareLocal(remote);

      PersonIdent who = new PersonIdent(ORCHESTRATOR, clock.instant());
      git.add().addFilepattern(".").call();
      git.add().setUpdate(true).addFilepattern(".").call();
      if (!git.status().call().isClean()) {
        git.commit()
            .setMessage("run " + ctx.run().id() + ": final changes")
            .setAuthor(who)
            .setCommitter(who)
            .setSign(false)
            .call();
      }

      git.fetch().setRemote("origin").call();
      ObjectId base = git.getRepository().resolve("refs/remotes/origin/" + baseBranch);
      MergeResult merge =
          git.merge()
              .include(base)
              .setCommit(true)
              .setMessage("Merge " + baseBranch + " into " + workspace.branch())
              .call();
      if (!merge.getMergeStatus().isSuccessful()) {
        git.reset().setMode(ResetCommand.ResetType.HARD).call(); // leave the workspace usable
        List<String> conflicts =
            merge.getConflicts() == null
                ? List.of()
                : new ArrayList<>(merge.getConflicts().keySet());
        throw new IllegalStateException(
            baseBranch
                + " moved on and conflicts with this run ("
                + merge.getMergeStatus()
                + "): "
                + String.join(", ", conflicts));
      }

      ObjectId head = git.getRepository().resolve("HEAD");
      Iterable<PushResult> results =
          git.push()
              .setRemote("origin")
              .setRefSpecs(
                  new RefSpec(workspace.branch() + ":refs/heads/" + baseBranch),
                  new RefSpec(workspace.branch() + ":refs/heads/" + workspace.branch()))
              .call();
      for (PushResult result : results) {
        for (RemoteRefUpdate update : result.getRemoteUpdates()) {
          if (update.getStatus() != RemoteRefUpdate.Status.OK
              && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
            throw new TransientAgentException(
                "push of "
                    + update.getRemoteName()
                    + " was "
                    + update.getStatus()
                    + (update.getMessage() == null ? "" : ": " + update.getMessage()));
          }
        }
      }

      String record =
          CanonicalJson.write(
              Map.of(
                  "branch",
                  workspace.branch(),
                  "baseBranch",
                  baseBranch,
                  "commit",
                  head.name(),
                  "remote",
                  remote));
      return AgentResult.success(
          "merged " + workspace.branch() + " into " + baseBranch + " at " + head.name(),
          ArtifactOutput.of(
              ArtifactTypes.PUBLISH_RECORD, record, "approved merge to " + baseBranch));
    }
  }

  /** A local origin must be a bare repository (no working tree to change underneath anyone). */
  static void refuseNonBareLocal(String remote) {
    if (remote == null) {
      throw new IllegalStateException("the workspace has no origin to publish to");
    }
    Path local = null;
    if (remote.startsWith("file:")) {
      local = Path.of(URI.create(remote));
    } else if (!remote.contains("://") && !remote.contains("@")) {
      local = Path.of(remote);
    }
    if (local != null && Files.isDirectory(local.resolve(".git"))) {
      throw new IllegalStateException(
          "refusing to publish into a working copy ("
              + local
              + "): point ORCH_SOURCE_REPO at a bare or remote repository");
    }
  }
}
