package com.acme.orchestrator.agents.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.StoredConfig;

/**
 * Creates each run's workspace: a JGit clone of the source repository at {@code
 * ORCH_WORKSPACE_ROOT/<runId>} with a new branch {@code agent/<runId>} from the base branch. (JGit
 * has no {@code git worktree}; a local clone gives the same isolation, and the source repository
 * stays untouched until publish merges the branch back.)
 */
public final class WorkspaceManager {

  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private final Path sourceRepo;
  private final Path workspaceRoot;
  private final String baseBranch;

  public WorkspaceManager(Path sourceRepo, Path workspaceRoot, String baseBranch) {
    this.sourceRepo = sourceRepo.toAbsolutePath().normalize();
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    this.baseBranch = Objects.requireNonNull(baseBranch, "baseBranch");
  }

  /** Creates the workspace, or opens it if the run already has one (a resumed run). */
  public Workspace create(String runId) {
    Path dir = dirFor(runId);
    Optional<Workspace> existing = open(runId);
    if (existing.isPresent()) {
      return existing.get();
    }
    try {
      Files.createDirectories(workspaceRoot);
      try (Git git =
          Git.cloneRepository()
              .setURI(sourceRepo.toUri().toString())
              .setDirectory(dir.toFile())
              .setBranch(baseBranch)
              .setNoCheckout(true)
              .call()) {
        // byte-exact files whatever the machine's git config (core.autocrlf on Windows would
        // rewrite line endings on checkout and restore, making diffs and checkpoints lie)
        StoredConfig config = git.getRepository().getConfig();
        config.setBoolean("core", null, "autocrlf", false);
        config.save();
        git.reset().setMode(ResetCommand.ResetType.HARD).call(); // check out the files
        ObjectId base = git.getRepository().resolve("HEAD");
        if (base == null) {
          throw new IllegalStateException(sourceRepo + " has no commits on " + baseBranch);
        }
        git.checkout().setCreateBranch(true).setName(branchFor(runId)).call();
        return new Workspace(runId, dir, branchFor(runId), base.name());
      }
    } catch (GitAPIException | IOException e) {
      throw new IllegalStateException("cannot create the workspace for run " + runId, e);
    }
  }

  public Optional<Workspace> open(String runId) {
    Path dir = dirFor(runId);
    if (!Files.isDirectory(dir.resolve(".git"))) {
      return Optional.empty();
    }
    try (Git git = Git.open(dir.toFile())) {
      ObjectId base = git.getRepository().resolve("refs/remotes/origin/" + baseBranch);
      if (base == null) {
        throw new IllegalStateException(dir + " has no origin/" + baseBranch);
      }
      // the base is where the agent branch left the base branch
      return Optional.of(new Workspace(runId, dir, branchFor(runId), base.name()));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot open the workspace of run " + runId, e);
    }
  }

  /** Removes the workspace (after the run is published or abandoned). */
  public void delete(String runId) {
    Path dir = dirFor(runId);
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        p.toFile().setWritable(true); // git marks pack files read-only on Windows
        Files.delete(p);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete the workspace of run " + runId, e);
    }
  }

  public static String branchFor(String runId) {
    return "agent/" + runId;
  }

  private Path dirFor(String runId) {
    if (runId == null || !RUN_ID.matcher(runId).matches()) {
      throw new IllegalArgumentException("not a usable run id for a workspace: " + runId);
    }
    return workspaceRoot.resolve(runId);
  }
}
