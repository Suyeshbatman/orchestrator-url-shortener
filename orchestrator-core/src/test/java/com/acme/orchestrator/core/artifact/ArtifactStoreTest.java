package com.acme.orchestrator.core.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactRef;
import com.acme.orchestrator.core.model.ProducedBy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactStoreTest {

  private final InMemoryArtifactStore store =
      new InMemoryArtifactStore(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

  private Artifact save(String stage, String type, String content, Artifact... inputs) {
    return store.save(
        new ArtifactDraft(
            "run-1",
            stage,
            type,
            content,
            ProducedBy.llm(stage + "Agent", "claude-sonnet-5-5", "prompt-hash"),
            List.of(inputs).stream().map(Artifact::ref).toList(),
            "because"));
  }

  @Test
  void sameStageAndTypeGetsTheNextVersionAndNothingIsOverwritten() {
    Artifact v1 = save("requirements", "requirements_spec", "{\"v\":1}");
    Artifact v2 = save("requirements", "requirements_spec", "{\"v\":2}");

    assertThat(v1.version()).isEqualTo(1);
    assertThat(v2.version()).isEqualTo(2);
    assertThat(v1.id()).isEqualTo("run-1:requirements:requirements_spec:v1");
    assertThat(store.versions("run-1", "requirements", "requirements_spec"))
        .containsExactly(v1, v2);
    assertThat(store.get(v1.id())).contains(v1); // v1 is still there
    assertThat(store.latest("run-1", "requirements", "requirements_spec")).contains(v2);
  }

  @Test
  void versionsAreCountedPerRunStageAndType() {
    save("requirements", "requirements_spec", "{}");
    Artifact other = save("requirements", "open_questions", "{}");
    Artifact otherRun =
        store.save(
            new ArtifactDraft(
                "run-2",
                "requirements",
                "requirements_spec",
                "{}",
                ProducedBy.deterministic("x"),
                List.of(),
                null));

    assertThat(other.version()).isEqualTo(1);
    assertThat(otherRun.version()).isEqualTo(1);
    assertThat(store.byRun("run-1")).hasSize(2);
    assertThat(store.latest("run-1", "design", "design_doc")).isEmpty();
  }

  @Test
  void latestOfTypeFindsUpstreamWorkFromAnyStage() {
    save("validation", "validation_report", "{\"run\":1}");
    Artifact second = save("validation", "validation_report", "{\"run\":2}");

    assertThat(store.latestOfType("run-1", "validation_report")).contains(second);
    assertThat(store.latestOfType("run-1", "nothing")).isEmpty();
  }

  @Test
  void inputsMustBeStoredAndMatchExactly() {
    Artifact spec = save("requirements", "requirements_spec", "{}");
    ArtifactRef unknown = new ArtifactRef("nope", "x", 1, "h");
    ArtifactRef wrongHash = new ArtifactRef(spec.id(), spec.type(), 1, "f".repeat(64));

    assertThatThrownBy(() -> saveWithInputs(List.of(unknown)))
        .hasMessageContaining("unknown input artifact nope");
    assertThatThrownBy(() -> saveWithInputs(List.of(wrongHash)))
        .hasMessageContaining("does not match the stored artifact");
    assertThat(store.byRun("run-1")).hasSize(1); // nothing saved
  }

  @Test
  void lineageReturnsTheAncestorGraphSourcesFirst() {
    Artifact specV1 = save("requirements", "requirements_spec", "{\"v\":1}");
    Artifact specV2 = save("requirements", "requirements_spec", "{\"v\":2}", specV1);
    Artifact tasks = save("decomposition", "task_graph", "{}", specV2);
    Artifact impact = save("impact_analysis", "impact_report", "{}", tasks);
    Artifact design = save("design", "design_doc", "{}", tasks, impact); // diamond
    Artifact code = save("implementation", "diff", "{}", design, specV2);
    save("docs_draft", "docs", "{}", design); // not an ancestor of code

    Lineage lineage = store.lineage(code.id());

    assertThat(lineage.root()).isEqualTo(code);
    assertThat(lineage.ancestors())
        .containsExactly(specV1, specV2, tasks, impact, design); // topological, sources first
    assertThat(lineage.graph().dependenciesOf(design.id()))
        .containsExactlyInAnyOrder(tasks.id(), impact.id());
    assertThat(lineage.graph().dependenciesOf(code.id()))
        .containsExactlyInAnyOrder(design.id(), specV2.id());
    assertThat(lineage.artifacts()).hasSize(6);
  }

  @Test
  void lineageOfASourceIsJustItself() {
    Artifact spec = save("requirements", "requirements_spec", "{}");

    Lineage lineage = store.lineage(spec.id());

    assertThat(lineage.ancestors()).isEmpty();
    assertThat(lineage.graph().nodes()).containsExactly(spec.id());
    assertThatThrownBy(() -> store.lineage("missing")).hasMessageContaining("unknown artifact");
  }

  private Artifact saveWithInputs(List<ArtifactRef> inputs) {
    return store.save(
        new ArtifactDraft(
            "run-1", "design", "design_doc", "{}", ProducedBy.deterministic("x"), inputs, ""));
  }
}
