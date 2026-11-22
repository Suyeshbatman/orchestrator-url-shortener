package com.acme.orchestrator.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.agents.llm.LlmRequest;
import com.acme.orchestrator.agents.llm.LlmResponse;
import com.acme.orchestrator.core.engine.Command;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.EventDraft;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.WorkflowLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

  @TempDir Path tmp;

  // ---- environment ----------------------------------------------------------------------------

  @Test
  void theRepoRootIsFoundAndTheEnvironmentWinsOverDotEnv() throws Exception {
    Files.createDirectories(tmp.resolve("config/workflows"));
    Files.writeString(tmp.resolve("config/workflows/sdlc-standard.yml"), "x");
    Files.writeString(
        tmp.resolve(".env"), "# comment\nORCH_LLM_MODEL=from-file\nANTHROPIC_API_KEY=\nA=1\n");
    Path deep = Files.createDirectories(tmp.resolve("orchestrator-cli/target"));

    Environment env = Environment.discover(deep, Map.of("ORCH_LLM_MODEL", "from-env"));

    assertThat(env.repoRoot()).isEqualTo(tmp.toAbsolutePath().normalize());
    assertThat(env.variables()).containsEntry("ORCH_LLM_MODEL", "from-env").containsEntry("A", "1");
    assertThat(env.has("ANTHROPIC_API_KEY")).isFalse(); // blank counts as missing
    assertThatThrownBy(() -> Environment.discover(Files.createTempDirectory("nowhere"), Map.of()))
        .hasMessageContaining("run this from inside the repository");
  }

  // ---- scenarios ------------------------------------------------------------------------------

  @Test
  void theGreenfieldScenarioLoads() {
    Scenario s = Scenario.load(Path.of("..", "scenarios"), "greenfield-qr-code");

    assertThat(s.mode()).isEqualTo(RunMode.GREENFIELD);
    assertThat(s.requirement()).startsWith("Add an endpoint that returns a QR code PNG");
    assertThat(s.expect()).isEqualTo("COMPLETED");
    assertThat(s.decisions()).isEmpty();
    assertThat(s.answersJson()).isEmpty();
  }

  @Test
  void scenarioFilesAreParsedStrictly() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("demo"));
    Files.writeString(dir.resolve("requirement.md"), "Do it.\n");
    Files.writeString(dir.resolve("answers.json"), "{\n  \"answers\" : [ ]\n}\n"); // canonicalized
    Files.writeString(
        dir.resolve("scenario.yml"),
        """
        mode: BROWNFIELD
        decisions:
          - { stage: design, decision: REJECT, comment: "use 410" }
        amend: { afterApprovalOf: design, text: "also X" }
        """);

    Scenario s = Scenario.load(tmp, "demo");
    assertThat(s.decisions())
        .containsExactly(new Scenario.ScriptedDecision("design", Decision.REJECT, "use 410"));
    assertThat(s.amend()).contains(new Scenario.Amendment("design", "also X"));
    assertThat(s.answersJson()).contains("{\"answers\":[]}");
    assertThat(s.base()).isEqualTo("main");
    assertThat(s.workflow()).isEqualTo("sdlc-standard");

    Files.writeString(dir.resolve("scenario.yml"), "workflow: sdlc-fanout\n");
    assertThat(Scenario.load(tmp, "demo").workflow()).isEqualTo("sdlc-fanout");
    Files.writeString(dir.resolve("scenario.yml"), "workflow: ../policies\n");
    assertThatThrownBy(() -> Scenario.load(tmp, "demo")).hasMessageContaining("bad workflow");

    Files.writeString(dir.resolve("scenario.yml"), "mode: GREENFIELD\ntypo: 1\n");
    assertThatThrownBy(() -> Scenario.load(tmp, "demo"))
        .hasMessageContaining("unknown keys: [typo]");
    assertThatThrownBy(() -> Scenario.load(tmp, "../x")).hasMessageContaining("not a scenario id");
    assertThatThrownBy(() -> Scenario.load(tmp, "missing"))
        .hasMessageContaining("no scenario missing");
  }

  // ---- hand-written replies -------------------------------------------------------------------

  @Test
  void handWrittenRepliesComeFirstThenTheModelOrAPendingPrompt() throws Exception {
    Path replies = Files.createDirectories(tmp.resolve("replies"));
    Files.writeString(replies.resolve("DesignAgent-1.json"), "{\"hand\":1}\n");
    List<String> live = new ArrayList<>();
    var withModel =
        new ScriptedLlmGateway(
            replies,
            Optional.of(r -> {
              live.add(r.agent());
              return new LlmResponse("{\"model\":1}", Optional.empty(), 1, 1, "m", Duration.ZERO);
            }));
    LlmRequest design = LlmRequest.of("DesignAgent", "s", "u", 10);

    assertThat(withModel.complete(design).text()).isEqualTo("{\"hand\":1}");
    assertThat(withModel.complete(design).text()).isEqualTo("{\"model\":1}"); // DesignAgent-2
    assertThat(live).containsExactly("DesignAgent");

    var withoutModel = new ScriptedLlmGateway(replies, Optional.empty());
    withoutModel.complete(design);
    assertThatThrownBy(() -> withoutModel.complete(design.withSchema("{\"type\":\"object\"}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no hand-written reply DesignAgent-2.json");
    assertThat(Files.readString(replies.resolve("pending/DesignAgent-2.prompt.md")))
        .contains("## System\ns")
        .contains("## User\nu")
        .contains("## JSON Schema");
  }

  @Test
  void theTasksOfAFanOutAreNumberedSeparately() throws Exception {
    Path replies = Files.createDirectories(tmp.resolve("replies"));
    Files.writeString(replies.resolve("ImplementationAgent-T1-1.json"), "{\"t\":1}");
    Files.writeString(replies.resolve("ImplementationAgent-T2-1.json"), "{\"t\":2}");
    var gateway = new ScriptedLlmGateway(replies, Optional.empty());
    LlmRequest impl = LlmRequest.of("ImplementationAgent", "s", "u", 10);

    // whichever task calls first gets its own reply
    assertThat(gateway.complete(impl.withTask(Optional.of("T2"))).text()).isEqualTo("{\"t\":2}");
    assertThat(gateway.complete(impl.withTask(Optional.of("T1"))).text()).isEqualTo("{\"t\":1}");
    assertThatThrownBy(() -> gateway.complete(impl.withTask(Optional.of("T1"))))
        .hasMessageContaining("no hand-written reply ImplementationAgent-T1-2.json");
  }

  // ---- the scripted human ---------------------------------------------------------------------

  @Test
  void theScriptedHumanAnswersDecidesAndAmendsOnce() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("s"));
    Files.writeString(dir.resolve("requirement.md"), "x");
    Files.writeString(
        dir.resolve("answers.json"),
        "{\"answers\":[{\"ambiguityId\":\"AMB-1\",\"answer\":\"block\"}]}");
    Files.writeString(
        dir.resolve("scenario.yml"),
        "decisions:\n  - { stage: design, decision: REJECT, comment: no }\namend: { afterApprovalOf: requirements, text: more }\n");
    Scenario scenario = Scenario.load(tmp, "s");
    List<Command> sent = new ArrayList<>();
    ScriptedHuman human =
        new ScriptedHuman(scenario, sent::add, Actor.human("alice"), Actor.human("bob"), s -> {});
    InMemoryEventStore events = new InMemoryEventStore(Clock.systemUTC());
    var wf = new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml"));
    events.append(
        EventDraft.run(
            "r",
            EventType.RUN_CREATED,
            Actor.SYSTEM,
            RunStateProjector.runCreatedPayload(
                Run.create("r", wf, RunMode.GREENFIELD, AutonomyLevel.L1_SUPERVISED))));
    for (EventType t : List.of(EventType.STAGE_READY, EventType.STAGE_STARTED)) {
      events.append(EventDraft.stage("r", t, "requirements", Actor.SYSTEM, Map.of()));
    }
    events.append(
        EventDraft.stage(
            "r",
            EventType.APPROVAL_REQUESTED,
            "requirements",
            Actor.SYSTEM,
            Map.of("reason", "human input needed", "needsInput", true)));

    assertThat(human.act(RunStateProjector.project(events.events("r")), events.events("r")))
        .isTrue();
    assertThat(sent.getFirst()).isInstanceOf(Command.ProvideAnswers.class);
    assertThat(human.act(RunStateProjector.project(events.events("r")), events.events("r")))
        .as("the same request is acted on once")
        .isFalse();

    events.append(
        EventDraft.stage("r", EventType.STAGE_INVALIDATED, "requirements", Actor.SYSTEM, Map.of()));
    events.append(
        EventDraft.stage("r", EventType.STAGE_READY, "requirements", Actor.SYSTEM, Map.of()));
    events.append(
        EventDraft.stage("r", EventType.STAGE_STARTED, "requirements", Actor.SYSTEM, Map.of()));
    events.append(
        EventDraft.stage(
            "r",
            EventType.APPROVAL_REQUESTED,
            "requirements",
            Actor.SYSTEM,
            Map.of("reason", "Scope sign-off")));
    human.act(RunStateProjector.project(events.events("r")), events.events("r"));
    assertThat(sent.get(1))
        .isEqualTo(
            new Command.ApprovalDecision(
                "requirements", Actor.human("alice"), "APPROVER", Decision.APPROVE, ""));

    events.append(
        EventDraft.stage(
            "r", EventType.APPROVAL_GRANTED, "requirements", Actor.human("alice"), Map.of()));
    human.act(RunStateProjector.project(events.events("r")), events.events("r"));
    assertThat(sent.get(2)).isEqualTo(new Command.AmendRequirement("more", Actor.human("bob")));
    human.act(RunStateProjector.project(events.events("r")), events.events("r"));
    assertThat(sent).hasSize(3); // amended once
  }

  // ---- the bare copy ---------------------------------------------------------------------------

  @Test
  void theRunPublishesToABareCopyAtTheBase() throws Exception {
    Path repo = tmp.resolve("repo");
    String first;
    try (Git git = Git.init().setInitialBranch("main").setDirectory(repo.toFile()).call()) {
      Files.writeString(repo.resolve("a.txt"), "1");
      git.add().addFilepattern(".").call();
      first = git.commit().setMessage("one").setSign(false).setAuthor("d", "d@e").call().getName();
      Files.writeString(repo.resolve("a.txt"), "2");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("two").setSign(false).setAuthor("d", "d@e").call();
    }

    String base = ScenarioRunner.bareCopy(repo, tmp.resolve("remote.git"), first);

    assertThat(base).isEqualTo(first);
    try (Git bare = Git.open(tmp.resolve("remote.git").toFile())) {
      assertThat(bare.getRepository().isBare()).isTrue();
      assertThat(bare.getRepository().resolve("refs/heads/main").getName()).isEqualTo(first);
    }
    assertThatThrownBy(() -> ScenarioRunner.bareCopy(repo, tmp.resolve("r2.git"), "nope"))
        .hasMessageContaining("no commit nope");

    // the demo moves main back before each scenario (the previous one published into it)
    Demo.resetMain(tmp.resolve("remote.git"), first);
    try (Git bare = Git.open(tmp.resolve("remote.git").toFile())) {
      assertThat(bare.getRepository().resolve("refs/heads/main").getName()).isEqualTo(first);
    }
  }
}
