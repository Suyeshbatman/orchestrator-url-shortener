package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.llm.LlmClient;
import com.acme.orchestrator.agents.llm.PromptTemplates;
import java.util.Objects;

/**
 * What every LLM agent needs.
 *
 * @param product one line describing the system being changed, used in prompts
 */
public record AgentKit(LlmClient llm, PromptTemplates prompts, String product) {

  public static final String SHORTENER =
      "a production URL shortener service (Java 21, Spring Boot 4, PostgreSQL, Redis)";

  public AgentKit {
    Objects.requireNonNull(llm, "llm");
    Objects.requireNonNull(prompts, "prompts");
    Objects.requireNonNull(product, "product");
  }
}
