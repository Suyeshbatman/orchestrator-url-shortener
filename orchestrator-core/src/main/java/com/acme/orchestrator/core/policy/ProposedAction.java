package com.acme.orchestrator.core.policy;

import java.util.Objects;

/** Something an agent wants to do, checked by the {@link PolicyEngine} before it happens. */
public sealed interface ProposedAction {

  /**
   * Write {@code content} to {@code path} (relative to the repository root).
   *
   * @param unreviewed the content is model output no human has seen yet (a live model, not a
   *     reviewed fixture or a hand-written reply); code like that is built and run by validation
   */
  record FileWrite(String path, String content, boolean unreviewed) implements ProposedAction {
    public FileWrite {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(content, "content");
    }

    /** Reviewed (or human-written) content. */
    public FileWrite(String path, String content) {
      this(path, content, false);
    }
  }

  /** A change set as a unified diff (paths in {@code --- a/…} / {@code +++ b/…} headers). */
  record Diff(String unifiedDiff, int filesChanged, int linesChanged) implements ProposedAction {
    public Diff {
      Objects.requireNonNull(unifiedDiff, "unifiedDiff");
      if (filesChanged < 0 || linesChanged < 0) {
        throw new IllegalArgumentException("diff sizes must not be negative");
      }
    }
  }

  /** Run a command line (no shell). */
  record Command(String cmdline) implements ProposedAction {
    public Command {
      Objects.requireNonNull(cmdline, "cmdline");
    }
  }

  /** Make the change public (merge to main). */
  record Publish(String description) implements ProposedAction {
    public Publish {
      description = description == null ? "" : description;
    }
  }
}
