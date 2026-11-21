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
 */
@ConfigurationProperties("orchestrator")
public record OrchestratorProperties(
    @DefaultValue("../config") Path configDir,
    @DefaultValue("../scenarios") Path scenariosDir,
    @DefaultValue("../.orchestrator") Path dataDir,
    @DefaultValue("../.agent-worktrees") Path workspaceRoot,
    @DefaultValue("..") String sourceRepo,
    @DefaultValue("main") String baseBranch,
    @DefaultValue("true") boolean recoverOnStartup,
    @DefaultValue("30s") Duration safeStopGrace) {

  public Path workflowsDir() {
    return configDir.resolve("workflows");
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
