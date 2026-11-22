package com.acme.orchestrator.agents.tools;

import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.Effect;
import com.acme.orchestrator.core.policy.ProposedAction;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a command line in the workspace, only if the policy engine ALLOWs it (CMD-001: an
 * exact-match allowlist). No shell: the line is split on whitespace and started directly, so pipes,
 * redirects and {@code &&} are just arguments. Secrets are removed from the child's environment.
 * Output (stdout and stderr) is captured up to a limit; on timeout the whole process tree is
 * killed.
 *
 * <p>{@code mvn} runs the workspace's Maven wrapper when there is one (the version the repository
 * pins; on Windows {@code mvnw.cmd}); other programs are looked up on the PATH. With a {@link
 * Sandbox}, the allowed command runs there instead of on this machine.
 */
public final class CommandTool {

  /** What the command did. {@code output} keeps the start and the end of a long log. */
  public record Result(int exitCode, String output, boolean timedOut, Duration duration) {
    public boolean succeeded() {
      return !timedOut && exitCode == 0;
    }
  }

  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
  static final int MAX_OUTPUT_CHARS = 200_000;

  private static final Pattern SECRET_NAME =
      Pattern.compile(
          "(?i).*(secret|password|passwd|token|api[_-]?key|private[_-]?key|credential).*");
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

  private final Workspace workspace;
  private final Authorizer authorizer;
  private final Duration timeout;
  private final Optional<Sandbox> sandbox;

  public CommandTool(
      Workspace workspace, Authorizer authorizer, Duration timeout, Optional<Sandbox> sandbox) {
    this.workspace = Objects.requireNonNull(workspace, "workspace");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
  }

  public CommandTool(Workspace workspace, Authorizer authorizer, Duration timeout) {
    this(workspace, authorizer, timeout, Optional.empty());
  }

  public CommandTool(Workspace workspace, Authorizer authorizer) {
    this(workspace, authorizer, DEFAULT_TIMEOUT);
  }

  /**
   * @throws PolicyDeniedException unless the policy ALLOWs the exact command line
   */
  public Result run(String cmdline) {
    String line = cmdline.strip();
    Decision decision = authorizer.authorize(new ProposedAction.Command(line));
    if (decision.effect() != Effect.ALLOW) {
      throw new PolicyDeniedException("running '" + line + "'", decision);
    }
    List<String> args = new ArrayList<>(Arrays.asList(line.split("\\s+")));
    if (sandbox.isPresent()) {
      return sandbox.get().run(List.copyOf(args), workspace.root(), timeout);
    }
    args.set(0, resolve(args.getFirst()));

    ProcessBuilder builder = new ProcessBuilder(args).directory(workspace.root().toFile());
    sanitize(builder.environment());
    return execute(builder, timeout, line);
  }

  /**
   * Starts the process, captures its output (stdout and stderr) and kills its whole tree when it
   * runs out of time or the attempt is cancelled.
   */
  static Result execute(ProcessBuilder builder, Duration timeout, String line) {
    builder.redirectErrorStream(true);
    long started = System.nanoTime();
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot start '" + line + "'", e);
    }
    StringBuilder output = new StringBuilder();
    Thread reader = Thread.ofVirtual().start(() -> capture(process.getInputStream(), output));
    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        killTree(process);
      }
      reader.join(Duration.ofSeconds(10));
    } catch (InterruptedException e) {
      killTree(process); // the attempt was cancelled: don't leave the build running
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running '" + line + "'", e);
    }
    String text;
    synchronized (output) {
      text = output.toString();
    }
    return new Result(
        finished ? process.exitValue() : -1,
        text,
        !finished,
        Duration.ofNanos(System.nanoTime() - started));
  }

  /** The wrapper for {@code mvn} when the workspace has one; otherwise a PATH lookup. */
  String resolve(String program) {
    if (program.equals("mvn")) {
      Path wrapper = workspace.root().resolve(WINDOWS ? "mvnw.cmd" : "mvnw");
      if (Files.isRegularFile(wrapper)) {
        return wrapper.toString();
      }
    }
    return onPath(program, System.getenv("PATH"), WINDOWS).orElse(program);
  }

  static Optional<String> onPath(String program, String path, boolean windows) {
    if (program.contains("/") || program.contains("\\") || path == null) {
      return Optional.empty();
    }
    List<String> extensions = windows ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
    for (String dir : path.split(windows ? ";" : ":")) {
      for (String ext : extensions) {
        Path candidate = Path.of(dir.isBlank() ? "." : dir, program + ext);
        if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
          return Optional.of(candidate.toString());
        }
      }
    }
    return Optional.empty();
  }

  /** Builds and tests don't need API keys or passwords. */
  static void sanitize(Map<String, String> env) {
    env.keySet().removeIf(name -> SECRET_NAME.matcher(name).matches());
  }

  private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Keeps the first half and the last half of the limit; the middle of a long build log is noise.
   */
  private static void capture(InputStream in, StringBuilder output) {
    byte[] buffer = new byte[8192];
    StringBuilder tail = new StringBuilder();
    int half = MAX_OUTPUT_CHARS / 2;
    long dropped = 0;
    try (in) {
      int n;
      while ((n = in.read(buffer)) > 0) {
        String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
        synchronized (output) {
          int room = half - output.length();
          if (room > 0) {
            output.append(chunk, 0, Math.min(room, chunk.length()));
            chunk = chunk.substring(Math.min(room, chunk.length()));
          }
          tail.append(chunk);
          if (tail.length() > half) {
            dropped += tail.length() - half;
            tail.delete(0, tail.length() - half);
          }
        }
      }
    } catch (IOException ignored) {
      // the process was killed
    }
    synchronized (output) {
      if (dropped > 0) {
        output.append("\n… [").append(dropped).append(" characters omitted] …\n");
      }
      output.append(tail);
    }
  }
}
