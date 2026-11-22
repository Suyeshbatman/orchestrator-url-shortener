package com.acme.orchestrator.app.run;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the orchestrator finds its rules and keeps its files.
 *
 * @param configDir policies.yml, autonomy.yml and workflows/
 * @param scenariosDir {@code <scenario>/replay/} fixtures for scenario runs
 * @param dataDir replay fixtures of ad-hoc runs ({@code runs/<run id>/replay/})
 * @param workspaceRoot one Git clone per run
 * @param sourceRepo what runs clone and publish to (a path or URI; publish needs it bare or remote)
 * @param recoverOnStartup resume unfinished runs when the app starts
 * @param operatorKeys {@code name:sha256hex(key)}, comma-separated: may start and steer runs
 * @param approverKeys same format: may decide approvals (a person can be in both lists)
 * @param validationSandbox where validation builds run: auto (the Docker sandbox for unreviewed
 *     model output), docker (always) or none
 */
@ConfigurationProperties("orchestrator")
public record OrchestratorProperties(
    @DefaultValue("../config") String configDir,
    @DefaultValue("../scenarios") String scenariosDir,
    @DefaultValue("../.orchestrator") String dataDir,
    @DefaultValue("../.agent-worktrees") String workspaceRoot,
    @DefaultValue("..") String sourceRepo,
    @DefaultValue("main") String baseBranch,
    @DefaultValue("true") boolean recoverOnStartup,
    @DefaultValue("30s") Duration safeStopGrace,
    @DefaultValue("") String operatorKeys,
    @DefaultValue("") String approverKeys,
    @DefaultValue("auto") String validationSandbox) {

  // Directories are bound as text and resolved against the file system here: Spring's own Path
  // conversion would also look on the classpath, where a "config" folder may exist.

  public Path configPath() {
    return Path.of(configDir);
  }

  public Path workflowsDir() {
    return configPath().resolve("workflows");
  }

  public Path scenariosPath() {
    return Path.of(scenariosDir);
  }

  public Path dataPath() {
    return Path.of(dataDir);
  }

  public Path workspacePath() {
    return Path.of(workspaceRoot);
  }

  /** A path becomes a file URI; anything with a scheme (https://, ssh://, file:) is used as is. */
  public String sourceUri() {
    return sourceRepo.contains("://")
            || sourceRepo.startsWith("git@")
            || sourceRepo.startsWith("file:")
        ? sourceRepo
        : Path.of(sourceRepo).toAbsolutePath().normalize().toUri().toString();
  }
}
