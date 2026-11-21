package com.acme.orchestrator.core.model;

/** Artifact type names shared by agents, gates and the app. Content shapes: BUILD_PLAN Step 5.3. */
public final class ArtifactTypes {

  /** The requirement as given ({@code {"text": "..."}}), stored before the run starts. */
  public static final String REQUIREMENT = "requirement";

  /** The stage id requirement artifacts are stored under (it is not a workflow stage). */
  public static final String INPUT_STAGE = "input";

  public static final String REQUIREMENTS_SPEC = "requirements_spec";
  public static final String TASK_GRAPH = "task_graph";
  public static final String IMPACT_REPORT = "impact_report";
  public static final String DESIGN_DOC = "design_doc";
  public static final String VALIDATION_REPORT = "validation_report";
  public static final String RELEASE_REPORT = "release_report";

  /** A human's answers to the spec's ambiguities ({@code {"answers": [{ambiguityId, answer}]}}). */
  public static final String ANSWERS = "answers";

  /** Code changes ({@code {"summary", "files": [{path, newContent}], "deletions": [path]}}). */
  public static final String PATCH = "patch";

  /** Tests written from the spec and design, same shape as {@link #PATCH}. */
  public static final String TEST_PATCH = "test_patch";

  /** Documentation changes, same shape as {@link #PATCH}. */
  public static final String DOCS = "docs";

  private ArtifactTypes() {}
}
