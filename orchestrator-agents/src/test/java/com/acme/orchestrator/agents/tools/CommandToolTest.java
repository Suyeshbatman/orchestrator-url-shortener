package com.acme.orchestrator.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.policy.PolicyEngine;
import com.acme.orchestrator.core.policy.PolicyLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandToolTest {

  /** CMD-001 as in config/policies.yml, plus two harmless programs for the test. */
  private static final PolicyEngine POLICIES =
      PolicyEngine.of(
          new PolicyLoader()
              .parse(
                  """
                  policies:
                    - id: CMD-001-command-allowlist
                      on: command
                      allow: ["java Hello.java", "java Sleep.java", "mvn -q -pl shortener-service test"]
                      effect_outside: DENY
                  """,
                  """
                  levels:
                    L0_SUGGEST: { description: s }
                    L1_SUPERVISED: { description: s }
                    L2_TRUSTED: { description: s }
                  invariants:
                    - publish requires human approval
                    - DENY policies are never overridable
                    - approvals must come from a human principal with role APPROVER
                  """));

  @TempDir Path tmp;
  private Workspace workspace;
  private final AtomicInteger asked = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    workspace =
        new WorkspaceManager(TempRepo.create(tmp), tmp.resolve("ws"), "main").create("run-1");
    Files.writeString(
        workspace.root().resolve("Hello.java"),
        "class Hello { public static void main(String[] a) { System.out.println(\"hi \" + a.length); } }");
    Files.writeString(
        workspace.root().resolve("Sleep.java"),
        "class Sleep { public static void main(String[] a) throws Exception { Thread.sleep(60_000); } }");
  }

  private CommandTool tool(Duration timeout) {
    return new CommandTool(
        workspace,
        action -> {
          asked.incrementAndGet();
          return POLICIES.evaluate(action, AutonomyLevel.L1_SUPERVISED);
        },
        timeout);
  }

  @Test
  void anAllowlistedCommandRunsInTheWorkspaceAndItsOutputIsCaptured() {
    CommandTool.Result result = tool(Duration.ofMinutes(1)).run("  java Hello.java ");

    assertThat(result.succeeded()).isTrue();
    assertThat(result.exitCode()).isZero();
    assertThat(result.output()).contains("hi 0");
    assertThat(result.timedOut()).isFalse();
  }

  @Test
  void aCommandNotOnTheAllowlistIsNeverStarted() {
    for (String cmd :
        new String[] {
          "java Hello.java && del /q *", "rm -rf .", "java Hello.java extra", "git push"
        }) {
      assertThatThrownBy(() -> tool(Duration.ofMinutes(1)).run(cmd))
          .isInstanceOf(PolicyDeniedException.class)
          .hasMessageContaining("not allowed (DENY)")
          .hasMessageContaining("not on the allowlist");
    }
    assertThat(asked).hasValue(4);
    assertThat(workspace.root().resolve("Hello.java")).exists();
  }

  @Test
  void aCommandThatRunsTooLongIsKilled() {
    CommandTool.Result result = tool(Duration.ofSeconds(2)).run("java Sleep.java");

    assertThat(result.timedOut()).isTrue();
    assertThat(result.succeeded()).isFalse();
    assertThat(result.duration()).isLessThan(Duration.ofSeconds(30));
  }

  @Test
  void mvnRunsTheWorkspacesMavenWrapperWhenThereIsOne() throws Exception {
    CommandTool tool = tool(Duration.ofMinutes(1));
    assertThat(tool.resolve("mvn")).doesNotContain("mvnw"); // no wrapper: PATH lookup

    Files.writeString(workspace.root().resolve("mvnw.cmd"), "@echo off\n");
    Files.writeString(workspace.root().resolve("mvnw"), "#!/bin/sh\n");

    assertThat(Path.of(tool.resolve("mvn")).getFileName().toString()).startsWith("mvnw");
    assertThat(Path.of(tool.resolve("mvn")).getParent()).isEqualTo(workspace.root());
  }

  @Test
  void programsAreFoundOnThePathWithWindowsExtensions() throws Exception {
    Path bin = Files.createDirectories(tmp.resolve("bin"));
    Files.writeString(bin.resolve("tool.cmd"), "@echo off\n");

    assertThat(CommandTool.onPath("tool", bin.toString(), true))
        .contains(bin.resolve("tool.cmd").toString());
    assertThat(CommandTool.onPath("missing", bin.toString(), true)).isEmpty();
    assertThat(CommandTool.onPath("..\\tool", bin.toString(), true)).isEmpty(); // no paths
  }

  @Test
  void secretsAreRemovedFromTheChildEnvironment() {
    Map<String, String> env = new HashMap<>();
    env.put("PATH", "/usr/bin");
    env.put("ANTHROPIC_API_KEY", "sk-ant-x");
    env.put("POSTGRES_PASSWORD", "pw");
    env.put("GITHUB_TOKEN", "t");
    env.put("JAVA_HOME", "/jdk");

    CommandTool.sanitize(env);

    assertThat(env).containsOnlyKeys("PATH", "JAVA_HOME");
  }
}
