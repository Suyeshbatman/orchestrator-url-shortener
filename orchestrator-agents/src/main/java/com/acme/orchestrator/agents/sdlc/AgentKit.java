package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.llm.LlmClient;
import com.acme.orchestrator.agents.llm.PromptTemplates;
import java.util.Objects;

/**
 * What every LLM agent needs.
 *
 * @param product one line describing the system being changed, used in prompts
 * @param unreviewedOutput the replies come from a live model that no human has reviewed (live and
 *     record modes), so the files the agents write are marked for review before anything builds
 *     them; replayed fixtures and hand-written replies were reviewed when they were committed
 */
public record AgentKit(
    LlmClient llm, PromptTemplates prompts, String product, boolean unreviewedOutput) {

  /** Reviewed output: replayed fixtures or hand-written replies. */
  public AgentKit(LlmClient llm, PromptTemplates prompts, String product) {
    this(llm, prompts, product, false);
  }

  public static final String SHORTENER =
      "a production URL shortener service (Java 21, Spring Boot 4, PostgreSQL, Redis)";

  public AgentKit {
    Objects.requireNonNull(llm, "llm");
    Objects.requireNonNull(prompts, "prompts");
    Objects.requireNonNull(product, "product");
  }
}
