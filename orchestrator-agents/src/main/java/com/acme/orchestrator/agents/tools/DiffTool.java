package com.acme.orchestrator.agents.tools;

import com.acme.orchestrator.core.policy.ProposedAction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

/**
 * The run's whole change: a unified diff of the working tree (committed checkpoints, uncommitted
 * edits and new files, minus what .gitignore excludes) against the base commit, with its size. This
 * is what the validation stage runs the policy engine on, and what an approver of publish sees.
 */
public final class DiffTool {

  /** The diff and its size: files touched, lines added plus removed. */
  public record Result(String unifiedDiff, int filesChanged, int linesChanged, List<String> paths) {

    public ProposedAction.Diff toAction() {
      return new ProposedAction.Diff(unifiedDiff, filesChanged, linesChanged);
    }

    public boolean isEmpty() {
      return filesChanged == 0;
    }
  }

  private final Workspace workspace;

  public DiffTool(Workspace workspace) {
    this.workspace = workspace;
  }

  public Result diff() {
    try (Git git = Git.open(workspace.root().toFile());
        ObjectReader reader = git.getRepository().newObjectReader();
        RevWalk walk = new RevWalk(reader)) {
      Repository repo = git.getRepository();
      CanonicalTreeParser base = new CanonicalTreeParser();
      base.reset(reader, walk.parseCommit(ObjectId.fromString(workspace.baseCommit())).getTree());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (DiffFormatter formatter = new DiffFormatter(out)) {
        formatter.setRepository(repo);
        formatter.setContext(3);
        // stage everything first: git add honours .gitignore (target/ etc. stay out of the diff)
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call(); // deletions
        List<DiffEntry> entries = formatter.scan(base, new DirCacheIterator(repo.readDirCache()));
        int lines = 0;
        for (DiffEntry entry : entries) {
          formatter.format(entry);
          for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
            lines += edit.getLengthA() + edit.getLengthB();
          }
        }
        List<String> paths =
            entries.stream()
                .map(e ->
                    e.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? e.getOldPath()
                        : e.getNewPath())
                .toList();
        return new Result(out.toString(StandardCharsets.UTF_8), entries.size(), lines, paths);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot diff the workspace of run " + workspace.runId(), e);
    } catch (GitAPIException e) {
      throw new IllegalStateException("cannot stage the workspace of run " + workspace.runId(), e);
    }
  }
}
