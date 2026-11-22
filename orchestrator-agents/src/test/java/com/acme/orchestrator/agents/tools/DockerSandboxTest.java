package com.acme.orchestrator.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerSandboxTest {

  private final DockerSandbox sandbox = new DockerSandbox();

  @Test
  void theBuildSeesOnlyTheWorkspaceTheCacheAndThePrivateDaemon() {
    Path ws = Path.of("ws").toAbsolutePath();
    List<String> command =
        sandbox.buildCommand(
            "sbx-build", "sbx", List.of("mvn", "-q", "-pl", "shortener-service", "verify"), ws);
    String line = String.join(" ", command);

    assertThat(line)
        .startsWith("docker run --rm --name sbx-build --network sbx")
        .contains("--cap-drop ALL", "--security-opt no-new-privileges", "--pids-limit 4096")
        .contains("--memory 6g", "--cpus 4")
        .contains("type=bind,source=" + ws + ",target=/work")
        .contains("type=volume,source=orchestrator-sandbox-m2,target=/root/.m2")
        .contains("-e DOCKER_HOST=tcp://docker:2375")
        .doesNotContain("docker.sock") // never this machine's Docker
        .doesNotContain("--privileged") // only the private daemon is privileged
        .endsWith("eclipse-temurin:21.0.11_10-jdk sh ./mvnw -q -pl shortener-service verify");
    // nothing of this machine's environment: only the variables the sandbox sets
    assertThat(command.stream().filter("-e"::equals).count())
        .isEqualTo(DockerSandbox.environment().size());
    assertThat(DockerSandbox.environment().keySet())
        .noneMatch(k -> k.matches("(?i).*(key|secret|token|password).*"));
  }

  @Test
  void otherCommandsRunAsGiven() {
    List<String> command =
        sandbox.buildCommand("b", "n", List.of("java", "-version"), Path.of("ws"));

    assertThat(command.subList(command.size() - 3, command.size()))
        .containsExactly("eclipse-temurin:21.0.11_10-jdk", "java", "-version");
  }
}
