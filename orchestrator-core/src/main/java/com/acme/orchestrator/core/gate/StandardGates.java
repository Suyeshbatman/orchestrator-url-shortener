package com.acme.orchestrator.core.gate;

import static com.acme.orchestrator.core.model.ArtifactTypes.DESIGN_DOC;
import static com.acme.orchestrator.core.model.ArtifactTypes.RELEASE_REPORT;
import static com.acme.orchestrator.core.model.ArtifactTypes.REQUIREMENT;
import static com.acme.orchestrator.core.model.ArtifactTypes.REQUIREMENTS_SPEC;
import static com.acme.orchestrator.core.model.ArtifactTypes.TASK_GRAPH;
import static com.acme.orchestrator.core.model.ArtifactTypes.VALIDATION_REPORT;

import com.acme.orchestrator.core.dag.CycleDetectedException;
import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.engine.GateContext;
import com.acme.orchestrator.core.engine.GateResult;
import com.acme.orchestrator.core.model.Artifact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gates of {@code sdlc-standard.yml}. They read artifact content in the shapes of BUILD_PLAN
 * Step 5.3; {@code validation_report} (produced by the deterministic ValidationAgent) is:
 *
 * <pre>{@code
 * { "build":    { "status": "SUCCESS" | "FAILURE", "summary": "..." },
 *   "tests":    { "total": 12, "failed": 0, "errors": 0, "skipped": 1,
 *                 "failures": [ { "test": "...", "message": "..." } ] },
 *   "coverage": { "line": 0.83 },
 *   "policy":   { "decision": "ALLOW" | "REQUIRE_APPROVAL" | "DENY",
 *                 "matchedPolicies": [ { "id": "SEC-001", "decision": "DENY", "reason": "..." } ] } }
 * }</pre>
 *
 * A missing artifact or a missing/mistyped field fails the gate with a reason naming it.
 */
final class StandardGates {

  static final int MAX_LISTED = 10;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private StandardGates() {}

  static GateRegistry.Builder register(GateRegistry.Builder registry, SchemaValidator schemas) {
    return registry
        .register("input.nonEmpty", simple(StandardGates::inputNonEmpty))
        .register("artifact.schemaValid", schemaValid(schemas))
        .register("spec.noBlockingQuestions", simple(StandardGates::noBlockingQuestions))
        .register("taskGraph.acyclic", simple(StandardGates::taskGraphAcyclic))
        .register("taskGraph.everyRequirementCovered", simple(StandardGates::requirementsCovered))
        .register("design.apiContractPresent", simple(StandardGates::apiContractPresent))
        .register("build.green", simple(StandardGates::buildGreen))
        .register("tests.green", simple(StandardGates::testsGreen))
        .register(
            "coverage.min",
            withArgument(StandardGates::checkCoverageArgument, StandardGates::coverageMin))
        .register("policy.noDeny", simple(StandardGates::policyNoDeny))
        .register("release.checklistComplete", simple(StandardGates::checklistComplete));
  }

  // ---- the gates ------------------------------------------------------------------------------

  static GateResult inputNonEmpty(GateContext context) {
    JsonNode text = load(context, REQUIREMENT).root().path("text");
    if (!text.isString() || text.stringValue().isBlank()) {
      return GateResult.fail("the requirement text is empty");
    }
    return GateResult.pass(
        "requirement has " + text.stringValue().strip().length() + " characters");
  }

  static Gate schemaValid(SchemaValidator schemas) {
    return withArgument(
        type -> {
          if (!schemas.supports(type)) {
            throw new IllegalArgumentException("no schema for artifact type '" + type + "'");
          }
        },
        (type, context) -> {
          // At exit, the attempt itself must have produced it; an older version doesn't count.
          Optional<Artifact> artifact =
              context.phase() == GateContext.Phase.EXIT
                  ? context.produced().reversed().stream()
                      .filter(a -> a.type().equals(type))
                      .findFirst()
                  : context.artifact(type);
          if (artifact.isEmpty()) {
            return GateResult.fail(
                context.phase() == GateContext.Phase.EXIT
                    ? "this attempt produced no " + type
                    : "no " + type + " artifact");
          }
          Loaded loaded = parse(artifact.get());
          List<String> problems = schemas.validate(type, loaded.artifact().contentJson());
          return problems.isEmpty()
              ? GateResult.pass(loaded.label() + " matches the " + type + " schema")
              : new GateResult(false, prefixed(loaded.label() + ": ", problems));
        });
  }

  static GateResult noBlockingQuestions(GateContext context) {
    Loaded spec = load(context, REQUIREMENTS_SPEC);
    List<String> blocking = new ArrayList<>();
    int nonBlocking = 0;
    for (JsonNode ambiguity : optionalArray(spec, "ambiguities")) {
      if ("BLOCKING".equalsIgnoreCase(text(ambiguity, "severity"))) {
        blocking.add(text(ambiguity, "id") + ": " + text(ambiguity, "question"));
      } else {
        nonBlocking++;
      }
    }
    if (!blocking.isEmpty()) {
      List<String> reasons = new ArrayList<>();
      reasons.add(blocking.size() + " blocking question(s) need an answer");
      reasons.addAll(limit(blocking));
      return new GateResult(false, reasons);
    }
    return GateResult.pass("no blocking questions (" + nonBlocking + " non-blocking)");
  }

  static GateResult taskGraphAcyclic(GateContext context) {
    Loaded graph = load(context, TASK_GRAPH);
    Map<String, JsonNode> tasks = tasksById(graph);
    List<String> problems = new ArrayList<>();
    Dag<String> dag = new Dag<>();
    tasks.keySet().forEach(dag::addNode);
    tasks.forEach((id, task) -> {
      for (String dependency : strings(task, "dependsOn", "task " + id)) {
        if (dependency.equals(id)) {
          problems.add("task " + id + " depends on itself");
        } else if (!tasks.containsKey(dependency)) {
          problems.add("task " + id + " depends on unknown task " + dependency);
        } else {
          dag.addEdge(dependency, id);
        }
      }
    });
    if (problems.isEmpty()) {
      try {
        dag.validateAcyclic();
      } catch (CycleDetectedException e) {
        problems.add(e.getMessage());
      }
    }
    return problems.isEmpty()
        ? GateResult.pass(tasks.size() + " tasks, no cycles")
        : new GateResult(false, limit(problems));
  }

  static GateResult requirementsCovered(GateContext context) {
    Loaded spec = load(context, REQUIREMENTS_SPEC);
    Set<String> requirements = new LinkedHashSet<>();
    for (String field : List.of("functional", "nonFunctional")) {
      List<JsonNode> items = optionalArray(spec, field);
      for (int i = 0; i < items.size(); i++) {
        String id = text(items.get(i), "id");
        if (id.isBlank()) {
          throw new Unmet(spec.label() + "." + field + "[" + i + "] has no id");
        }
        requirements.add(id);
      }
    }
    if (requirements.isEmpty()) {
      return GateResult.fail(spec.label() + " lists no requirements");
    }
    Map<String, JsonNode> tasks = tasksById(load(context, TASK_GRAPH));
    Set<String> covered = new LinkedHashSet<>();
    List<String> problems = new ArrayList<>();
    tasks.forEach((id, task) -> {
      for (String requirement : strings(task, "requirementIds", "task " + id)) {
        if (requirements.contains(requirement)) {
          covered.add(requirement);
        } else {
          problems.add("task " + id + " refers to unknown requirement " + requirement);
        }
      }
    });
    requirements.stream()
        .filter(r -> !covered.contains(r))
        .forEach(r -> problems.add(r + " is not covered by any task"));
    return problems.isEmpty()
        ? GateResult.pass("all " + requirements.size() + " requirements are covered")
        : new GateResult(false, limit(problems));
  }

  static GateResult apiContractPresent(GateContext context) {
    Loaded design = load(context, DESIGN_DOC);
    JsonNode api = design.root().path("apiChanges");
    boolean present =
        api.isString()
            ? !api.stringValue().isBlank()
            : ((api.isObject() || api.isArray()) && !api.isEmpty());
    return present
        ? GateResult.pass(design.label() + " has an API contract")
        : GateResult.fail(
            design.label()
                + ".apiChanges is missing or empty: give the OpenAPI fragment, or state that the"
                + " API does not change");
  }

  static GateResult buildGreen(GateContext context) {
    JsonNode build = object(load(context, VALIDATION_REPORT), "build");
    String status = text(build, "status");
    if ("SUCCESS".equals(status)) {
      return GateResult.pass("build succeeded");
    }
    List<String> reasons = new ArrayList<>();
    reasons.add("build status is " + (status.isBlank() ? "missing" : status));
    String summary = text(build, "summary");
    if (!summary.isBlank()) {
      reasons.add(summary);
    }
    return new GateResult(false, reasons);
  }

  static GateResult testsGreen(GateContext context) {
    Loaded report = load(context, VALIDATION_REPORT);
    JsonNode tests = object(report, "tests");
    long total = count(tests, "total", "tests");
    long failed = count(tests, "failed", "tests");
    long errors = count(tests, "errors", "tests");
    if (total == 0) {
      return GateResult.fail("no tests ran");
    }
    if (failed + errors > 0) {
      List<String> reasons = new ArrayList<>();
      reasons.add(failed + " failed and " + errors + " errored of " + total + " tests");
      List<String> listed = new ArrayList<>();
      for (JsonNode failure : optionalArray(tests, "failures", "validation_report.tests")) {
        listed.add(text(failure, "test") + ": " + text(failure, "message"));
      }
      reasons.addAll(limit(listed));
      return new GateResult(false, reasons);
    }
    long skipped = tests.path("skipped").isIntegralNumber() ? tests.path("skipped").longValue() : 0;
    return GateResult.pass(total + " tests passed (" + skipped + " skipped)");
  }

  static void checkCoverageArgument(String argument) {
    BigDecimal min;
    try {
      min = new BigDecimal(argument);
    } catch (NumberFormatException e) {
      min = null;
    }
    if (min == null || min.signum() < 0 || min.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(
          "needs a fraction between 0 and 1, e.g. coverage.min:0.80, got '" + argument + "'");
    }
  }

  static GateResult coverageMin(String argument, GateContext context) {
    BigDecimal min = new BigDecimal(argument);
    JsonNode line = object(load(context, VALIDATION_REPORT), "coverage").path("line");
    if (!line.isNumber()
        || line.decimalValue().signum() < 0
        || line.decimalValue().compareTo(BigDecimal.ONE) > 0) {
      throw new Unmet("validation_report.coverage.line must be a fraction between 0 and 1");
    }
    BigDecimal actual = line.decimalValue();
    return actual.compareTo(min) >= 0
        ? GateResult.pass("line coverage " + plain(actual) + " >= " + plain(min))
        : GateResult.fail("line coverage " + plain(actual) + " is below " + plain(min));
  }

  static GateResult policyNoDeny(GateContext context) {
    Loaded report = load(context, VALIDATION_REPORT);
    JsonNode policy = object(report, "policy");
    String decision = text(policy, "decision");
    switch (decision) {
      case "ALLOW", "REQUIRE_APPROVAL" -> {
        return GateResult.pass("policy decision " + decision);
      }
      case "DENY" -> {
        List<String> reasons = new ArrayList<>();
        reasons.add("policy decision DENY");
        List<String> denied = new ArrayList<>();
        for (JsonNode match :
            optionalArray(policy, "matchedPolicies", "validation_report.policy")) {
          if ("DENY".equals(text(match, "decision"))) {
            denied.add(text(match, "id") + ": " + text(match, "reason"));
          }
        }
        reasons.addAll(limit(denied));
        return new GateResult(false, reasons);
      }
      default -> {
        return GateResult.fail(
            "unknown policy decision '"
                + decision
                + "' (expected ALLOW, REQUIRE_APPROVAL or DENY)");
      }
    }
  }

  static GateResult checklistComplete(GateContext context) {
    Loaded report = load(context, RELEASE_REPORT);
    List<JsonNode> checklist = optionalArray(report, "checklist");
    if (checklist.isEmpty()) {
      return GateResult.fail("the release checklist is empty");
    }
    List<String> open = new ArrayList<>();
    for (JsonNode entry : checklist) {
      String status = text(entry, "status").toUpperCase(Locale.ROOT);
      if (!status.equals("DONE") && !status.equals("NOT_APPLICABLE")) {
        open.add(text(entry, "item") + ": " + (status.isBlank() ? "no status" : status));
      }
    }
    if (open.isEmpty()) {
      return GateResult.pass("all " + checklist.size() + " checklist items are done");
    }
    List<String> reasons = new ArrayList<>();
    reasons.add(open.size() + " checklist item(s) are not done");
    reasons.addAll(limit(open));
    return new GateResult(false, reasons);
  }

  // ---- building gates -------------------------------------------------------------------------

  /** A gate that takes no argument. {@link Unmet} thrown by the body becomes a failed result. */
  static Gate simple(Function<GateContext, GateResult> body) {
    return (argument, context) -> guarded(() -> body.apply(context));
  }

  /** A gate that requires an argument, checked by {@code check} at load time. */
  static Gate withArgument(
      Consumer<String> check, BiFunction<String, GateContext, GateResult> body) {
    return new Gate() {
      @Override
      public GateResult evaluate(Optional<String> argument, GateContext context) {
        return guarded(() -> body.apply(argument.orElseThrow(), context));
      }

      @Override
      public void checkArgument(Optional<String> argument) {
        check.accept(argument.orElseThrow(() -> new IllegalArgumentException("needs an argument")));
      }
    };
  }

  private static GateResult guarded(Supplier<GateResult> body) {
    try {
      return body.get();
    } catch (Unmet e) {
      return GateResult.fail(e.getMessage());
    }
  }

  // ---- reading artifacts ----------------------------------------------------------------------

  /**
   * Content of an artifact the gate needs; {@code label} names it in reasons, e.g. task_graph v2.
   */
  record Loaded(Artifact artifact, JsonNode root) {
    String label() {
      return artifact.type() + " v" + artifact.version();
    }
  }

  /** A precondition of the gate isn't met (artifact missing, field missing or mistyped). */
  static final class Unmet extends RuntimeException {
    Unmet(String message) {
      super(message, null, false, false);
    }
  }

  static Loaded load(GateContext context, String type) {
    return parse(context.artifact(type).orElseThrow(() -> new Unmet("no " + type + " artifact")));
  }

  static Loaded parse(Artifact artifact) {
    JsonNode root;
    try {
      root = JSON.readTree(artifact.contentJson());
    } catch (JacksonException e) {
      throw new Unmet(
          artifact.type()
              + " v"
              + artifact.version()
              + " is not valid JSON: "
              + e.getOriginalMessage());
    }
    if (root == null || !root.isObject()) {
      throw new Unmet(artifact.type() + " v" + artifact.version() + " must be a JSON object");
    }
    return new Loaded(artifact, root);
  }

  private static JsonNode object(Loaded loaded, String field) {
    JsonNode node = loaded.root().path(field);
    if (!node.isObject()) {
      throw new Unmet(loaded.artifact().type() + "." + field + " is missing");
    }
    return node;
  }

  /** Missing or null counts as empty; anything but a list is an error. */
  private static List<JsonNode> optionalArray(Loaded loaded, String field) {
    return optionalArray(loaded.root(), field, loaded.label());
  }

  private static List<JsonNode> optionalArray(JsonNode node, String field, String where) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new Unmet(where + "." + field + " must be a list");
    }
    List<JsonNode> items = new ArrayList<>();
    value.forEach(items::add);
    return items;
  }

  private static List<String> strings(JsonNode node, String field, String where) {
    List<String> values = new ArrayList<>();
    for (JsonNode item : optionalArray(node, field, where)) {
      if (!item.isString() || item.stringValue().isBlank()) {
        throw new Unmet(where + "." + field + " must hold non-empty strings");
      }
      values.add(item.stringValue());
    }
    return values;
  }

  private static Map<String, JsonNode> tasksById(Loaded graph) {
    Map<String, JsonNode> tasks = new LinkedHashMap<>();
    List<JsonNode> items = optionalArray(graph, "tasks");
    if (items.isEmpty()) {
      throw new Unmet(graph.label() + " has no tasks");
    }
    for (int i = 0; i < items.size(); i++) {
      String id = text(items.get(i), "id");
      if (id.isBlank()) {
        throw new Unmet(graph.label() + ".tasks[" + i + "] has no id");
      }
      if (tasks.putIfAbsent(id, items.get(i)) != null) {
        throw new Unmet(graph.label() + " has duplicate task id " + id);
      }
    }
    return tasks;
  }

  private static long count(JsonNode node, String field, String where) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber() || value.longValue() < 0) {
      throw new Unmet("validation_report." + where + "." + field + " must be a whole number");
    }
    return value.longValue();
  }

  /** A string field, or "" when missing or not a string. */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isString() ? value.stringValue() : "";
  }

  private static List<String> limit(List<String> reasons) {
    if (reasons.size() <= MAX_LISTED) {
      return reasons;
    }
    List<String> limited = new ArrayList<>(reasons.subList(0, MAX_LISTED));
    limited.add("… and " + (reasons.size() - MAX_LISTED) + " more");
    return limited;
  }

  private static List<String> prefixed(String prefix, List<String> reasons) {
    return limit(reasons.stream().map(r -> prefix + r).collect(Collectors.toList()));
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
