package com.acme.orchestrator.agents.tools;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the validation build in throw-away containers instead of on this machine.
 *
 * <ul>
 *   <li>A private Docker daemon ({@code docker:dind}) on a network of its own: the build's
 *       Testcontainers tests start their databases there. The build never gets this machine's
 *       Docker socket, which would amount to administrator access.
 *   <li>The build itself in a JDK image: only the run's workspace (at {@code /work}) and a Maven
 *       cache volume are mounted; no home directory, no {@code .env}, no credentials. Only the
 *       variables below are set. All Linux capabilities are dropped, privileges can't be gained,
 *       and CPU, memory and process count are limited.
 *   <li>Everything is removed afterwards, also on a timeout or an error.
 * </ul>
 *
 * <p>Network access remains: Maven and the test images have to be downloaded. That is the residual
 * risk (see docs/security.md, O-1).
 */
public final class DockerSandbox implements Sandbox {

  /**
   * @param dindImage the private daemon, pinned
   * @param buildImage the JDK the build runs on, pinned; Maven comes from the workspace's wrapper
   * @param mavenCacheVolume a named volume kept between builds, so dependencies download once
   */
  public record Settings(
      String dindImage,
      String buildImage,
      String mavenCacheVolume,
      String memory,
      String cpus,
      int pidsLimit,
      Duration daemonStartTimeout) {

    public Settings {
      Objects.requireNonNull(dindImage, "dindImage");
      Objects.requireNonNull(buildImage, "buildImage");
      Objects.requireNonNull(mavenCacheVolume, "mavenCacheVolume");
    }

    public static final Settings DEFAULTS =
        new Settings(
            "docker:29.7.2-dind",
            "eclipse-temurin:21.0.11_10-jdk",
            "orchestrator-sandbox-m2",
            "6g",
            "4",
            4096,
            Duration.ofSeconds(90));
  }

  private static final Duration DOCKER_CALL = Duration.ofMinutes(5);

  private final Settings settings;

  public DockerSandbox(Settings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  public DockerSandbox() {
    this(Settings.DEFAULTS);
  }

  @Override
  public CommandTool.Result run(List<String> args, Path workspace, Duration timeout) {
    String id = "orch-sbx-" + HexFormat.of().formatHex(new SecureRandom().generateSeed(4));
    String network = id;
    String daemon = id + "-docker";
    String build = id + "-build";
    StringBuilder log = new StringBuilder();
    try {
      docker(log, "network", "create", network);
      docker(
          log,
          "run",
          "-d",
          "--privileged", // dind needs it; it runs inside Docker's VM, and only the daemon has it
          "--name",
          daemon,
          "--network",
          network,
          "--network-alias",
          "docker",
          "-e",
          "DOCKER_TLS_CERTDIR=", // plain TCP on the private network only
          settings.dindImage());
      awaitDaemon(daemon, log);
      CommandTool.Result result =
          CommandTool.execute(
              new ProcessBuilder(buildCommand(build, network, args, workspace)),
              timeout,
              String.join(" ", args));
      return new CommandTool.Result(
          result.exitCode(), result.output(), result.timedOut(), result.duration());
    } catch (SandboxException e) {
      return new CommandTool.Result(
          -1,
          "the sandbox could not run the build: " + e.getMessage() + "\n" + log,
          false,
          Duration.ZERO);
    } finally {
      quietly("rm", "-f", build);
      quietly("rm", "-f", "-v", daemon);
      quietly("network", "rm", network);
    }
  }

  /** The {@code docker run} for the build container. */
  List<String> buildCommand(String name, String network, List<String> args, Path workspace) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "docker",
                "run",
                "--rm",
                "--name",
                name,
                "--network",
                network,
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--memory",
                settings.memory(),
                "--cpus",
                settings.cpus(),
                "--pids-limit",
                Integer.toString(settings.pidsLimit()),
                "--mount",
                "type=bind,source=" + workspace.toAbsolutePath() + ",target=/work",
                "--mount",
                "type=volume,source=" + settings.mavenCacheVolume() + ",target=/root/.m2",
                "-w",
                "/work"));
    for (Map.Entry<String, String> e : environment().entrySet()) {
      command.addAll(List.of("-e", e.getKey() + "=" + e.getValue()));
    }
    command.add(settings.buildImage());
    if (args.getFirst().equals("mvn")) {
      command.addAll(List.of("sh", "./mvnw")); // the Maven version the repository pins
      command.addAll(args.subList(1, args.size()));
    } else {
      command.addAll(args);
    }
    return command;
  }

  /** The only variables the build sees: where Docker is, nothing else of this machine's. */
  static Map<String, String> environment() {
    return Map.of(
        "DOCKER_HOST", "tcp://docker:2375",
        "TESTCONTAINERS_HOST_OVERRIDE", "docker",
        // the daemon is thrown away after the build, which removes every container anyway
        "TESTCONTAINERS_RYUK_DISABLED", "true",
        "MAVEN_OPTS", "-Xmx1g");
  }

  private void awaitDaemon(String daemon, StringBuilder log) {
    long deadline = System.nanoTime() + settings.daemonStartTimeout().toNanos();
    while (System.nanoTime() < deadline) {
      if (CommandTool.execute(
              new ProcessBuilder("docker", "exec", daemon, "docker", "info"),
              Duration.ofSeconds(15),
              "docker info")
          .succeeded()) {
        return;
      }
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SandboxException("interrupted while the private Docker daemon started");
      }
    }
    log.append("the private Docker daemon did not start within ")
        .append(settings.daemonStartTimeout())
        .append('\n');
    throw new SandboxException("the private Docker daemon did not start");
  }

  private static void docker(StringBuilder log, String... args) {
    List<String> command = new ArrayList<>(List.of("docker"));
    command.addAll(List.of(args));
    CommandTool.Result result =
        CommandTool.execute(new ProcessBuilder(command), DOCKER_CALL, String.join(" ", command));
    if (!result.succeeded()) {
      log.append(String.join(" ", command)).append(":\n").append(result.output()).append('\n');
      throw new SandboxException(String.join(" ", command) + " failed");
    }
  }

  private static void quietly(String... args) {
    List<String> command = new ArrayList<>(List.of("docker"));
    command.addAll(List.of(args));
    CommandTool.execute(new ProcessBuilder(command), Duration.ofMinutes(1), "cleanup");
  }

  private static final class SandboxException extends RuntimeException {
    SandboxException(String message) {
      super(message);
    }
  }
}
