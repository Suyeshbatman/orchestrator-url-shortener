package com.acme.orchestrator.app.api;

import java.net.URI;

/** Stable Problem Details {@code type} URIs. Changing one is a breaking API change. */
public final class ProblemTypes {

  public record Type(URI uri, String title) {}

  public static final Type UNAUTHORIZED = type("unauthorized", "Unauthorized");
  public static final Type FORBIDDEN = type("forbidden", "Forbidden");
  public static final Type VALIDATION_FAILED = type("validation-failed", "Validation failed");
  public static final Type MALFORMED_REQUEST = type("malformed-request", "Malformed request");
  public static final Type RUN_NOT_FOUND = type("run-not-found", "Run not found");
  public static final Type RUN_NOT_ACTIVE = type("run-not-active", "Run is not active");
  public static final Type SCENARIO_NOT_FOUND = type("scenario-not-found", "Scenario not found");
  public static final Type ARTIFACT_NOT_FOUND = type("artifact-not-found", "Artifact not found");
  public static final Type APPROVAL_NOT_FOUND = type("approval-not-found", "Approval not found");
  public static final Type APPROVAL_NOT_PENDING =
      type("approval-not-pending", "Approval already decided");
  public static final Type RUN_START_FAILED = type("run-start-failed", "The run could not start");
  public static final Type INTERNAL_ERROR = type("internal-error", "Internal error");

  private ProblemTypes() {}

  private static Type type(String slug, String title) {
    return new Type(URI.create("/problems/" + slug), title);
  }
}
