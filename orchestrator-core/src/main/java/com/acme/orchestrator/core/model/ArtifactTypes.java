package com.acme.orchestrator.core.model;

/** Artifact type names shared by agents, gates and the app. Content shapes: BUILD_PLAN Step 5.3. */
public final class ArtifactTypes {

  /** The requirement as given ({@code {"text": "..."}}), stored before the run starts. */
  public static final String REQUIREMENT = "requirement";

  public static final String REQUIREMENTS_SPEC = "requirements_spec";
  public static final String TASK_GRAPH = "task_graph";
  public static final String IMPACT_REPORT = "impact_report";
  public static final String DESIGN_DOC = "design_doc";
  public static final String VALIDATION_REPORT = "validation_report";
  public static final String RELEASE_REPORT = "release_report";

  private ArtifactTypes() {}
}
