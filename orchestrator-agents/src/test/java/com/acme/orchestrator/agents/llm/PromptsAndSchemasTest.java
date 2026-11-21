package com.acme.orchestrator.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.agents.llm.PromptTemplates.Prompt;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptsAndSchemasTest {

  private final PromptTemplates prompts = new PromptTemplates();
  private final JsonSchemas schemas = new JsonSchemas();

  @Test
  void aTemplateRendersItsSectionsWithValues() {
    Prompt prompt =
        prompts.render(
            "TestAgent", Map.of("product", "a URL shortener", "requirement", "QR codes $1"));

    assertThat(prompt.system()).isEqualTo("You turn requirements into a spec for a URL shortener.");
    assertThat(prompt.user()).isEqualTo("Requirement:\nQR codes $1");
    assertThat(prompt.hash()).matches("[0-9a-f]{64}");
    assertThat(prompts.render("TestAgent", Map.of("product", "x", "requirement", "y")).hash())
        .isNotEqualTo(prompt.hash());
  }

  @Test
  void templateProblemsAreErrors() {
    assertThatThrownBy(() -> prompts.render("TestAgent", Map.of("product", "x")))
        .hasMessage("prompt for TestAgent has no value for: requirement");
    assertThatThrownBy(() -> prompts.render("Broken", Map.of()))
        .hasMessageContaining("needs a '## System' and then a '## User' section");
    assertThatThrownBy(() -> prompts.render("NoSuchAgent", Map.of()))
        .hasMessage("no prompt template prompts/NoSuchAgent.md");
    assertThatThrownBy(() -> prompts.render("../etc", Map.of()))
        .hasMessage("not an agent name: ../etc");
  }

  @Test
  void schemasValidateContentAndPointAtTheProblem() {
    assertThat(schemas.supports("test_spec")).isTrue();
    assertThat(schemas.supports("unknown_type")).isFalse();
    assertThat(schemas.supports("../secrets")).isFalse();
    assertThat(schemas.text("test_spec")).contains("problemStatement");

    assertThat(
            schemas.validate(
                "test_spec",
                "{\"problemStatement\":\"QR\",\"functional\":[{\"id\":\"FR-1\",\"text\":\"t\"}]}"))
        .isEmpty();
    assertThat(schemas.validate("test_spec", "{\"functional\":[{\"id\":\"F1\",\"text\":\"t\"}]}"))
        .hasSize(2)
        .anySatisfy(p -> assertThat(p).contains("problemStatement"))
        .anySatisfy(p -> assertThat(p).contains("/functional/0/id"));
    assertThat(schemas.validate("test_spec", "{not json"))
        .singleElement()
        .asString()
        .startsWith("not valid JSON");
    assertThatThrownBy(() -> schemas.validate("unknown_type", "{}"))
        .hasMessage("no schema for artifact type 'unknown_type'");
  }
}
