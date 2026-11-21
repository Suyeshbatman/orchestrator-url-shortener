package com.acme.orchestrator.core.gate;

import java.util.List;

/**
 * Port: validates artifact content against the JSON Schema for its type. The JSON Schema
 * implementation lives in orchestrator-agents (Step 5.1), next to the schemas the agents are
 * prompted with; {@link #STRUCTURE_ONLY} only requires a JSON object.
 */
public interface SchemaValidator {

  /** Knows every type; checks nothing beyond "a JSON object" (which the gate checks itself). */
  SchemaValidator STRUCTURE_ONLY =
      new SchemaValidator() {
        @Override
        public boolean supports(String artifactType) {
          return true;
        }

        @Override
        public List<String> validate(String artifactType, String contentJson) {
          return List.of();
        }
      };

  /** Whether a schema exists for {@code artifactType}; checked when a workflow is loaded. */
  boolean supports(String artifactType);

  /** Problems found, each locating the offending value; empty when the content is valid. */
  List<String> validate(String artifactType, String contentJson);
}
