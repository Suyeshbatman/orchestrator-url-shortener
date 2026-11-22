package com.acme.orchestrator.cli;

import com.acme.orchestrator.agents.sdlc.ValidationAgent;
import com.acme.orchestrator.cli.ScenarioRunner.LlmMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Entry point for the demo/operator CLI. */
public final class Main {

  private static final String USAGE =
      """
      Usage: orchestrator-cli <command> [args]

      Commands:
        demo [--url http://localhost:8086] [--timeout 20m]
                               The end-to-end demo in replay mode, no API key: docker compose up,
                               the orchestrator app on a fresh database, the three scenarios
                               through its API with scripted humans, audit checks, metrics.
        run-scenario <id> [--llm replay|record|handwritten|live] [--timeout 60m]
                     [--sandbox auto|docker|none]
                               Run one scenario end to end in this process (scripted humans,
                               real agents, real Git and Maven). Default --llm replay.
                                 record       call the model and save scenarios/<id>/replay/
                                              (needs ANTHROPIC_API_KEY; replies/ take precedence)
                                 handwritten  only replies/<Agent>-<n>.json, no API key; a missing
                                              reply's prompt is written to replies/pending/
                               --sandbox: where validation builds run; auto = the Docker
                                 sandbox for record and live output, this machine otherwise
        record-fixtures <id>   Same as run-scenario <id> --llm handwritten
        verify-audit <runId> --key <api key> [--url http://localhost:8086]
                               Recompute a run's hash chain on a running orchestrator
        metrics --key <api key> [--url http://localhost:8086]
                               Print the reliability metrics summary of a running orchestrator
      """;
  private static final String DEFAULT_URL = "http://localhost:8086";

  private Main() {}

  public static void main(String[] args) throws Exception {
    System.exit(run(Arrays.asList(args)));
  }

  static int run(List<String> args) throws Exception {
    if (args.isEmpty()) {
      System.out.print(USAGE);
      return 2;
    }
    switch (args.getFirst()) {
      case "run-scenario", "record-fixtures" -> {
        if (args.size() < 2) {
          System.out.print(USAGE);
          return 2;
        }
        LlmMode mode =
            args.getFirst().equals("record-fixtures")
                ? LlmMode.HANDWRITTEN
                : LlmMode.valueOf(option(args, "--llm", "replay").toUpperCase(Locale.ROOT));
        ScenarioRunner.Outcome outcome =
            new ScenarioRunner(environment(), System.out)
                .run(
                    args.get(1),
                    mode,
                    duration(option(args, "--timeout", "60m")),
                    ValidationAgent.Sandboxing.parse(option(args, "--sandbox", "auto")));
        return outcome.expected() && outcome.audit().valid() ? 0 : 1;
      }
      case "demo" -> {
        return new Demo(environment(), System.out, URI.create(option(args, "--url", DEFAULT_URL)))
            .run(duration(option(args, "--timeout", "20m")));
      }
      case "verify-audit" -> {
        if (args.size() < 2 || option(args, "--key", null) == null) {
          System.out.print(USAGE);
          return 2;
        }
        JsonNode audit = api(args).get("/api/runs/" + args.get(1) + "/audit/verify");
        System.out.println(pretty(audit));
        return audit.path("valid").asBoolean() ? 0 : 1;
      }
      case "metrics" -> {
        if (option(args, "--key", null) == null) {
          System.out.print(USAGE);
          return 2;
        }
        System.out.println(pretty(api(args).get("/api/metrics/summary")));
        return 0;
      }
      default -> {
        System.out.print(USAGE);
        return 2;
      }
    }
  }

  private static Environment environment() {
    return Environment.discover(Path.of(""), System.getenv());
  }

  private static OrchestratorApi api(List<String> args) {
    return new OrchestratorApi(
        URI.create(option(args, "--url", DEFAULT_URL)), option(args, "--key", null));
  }

  private static Duration duration(String text) {
    return Duration.parse("PT" + text.toUpperCase(Locale.ROOT));
  }

  private static String pretty(JsonNode node) {
    return JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(node);
  }

  private static String option(List<String> args, String name, String fallback) {
    int i = args.indexOf(name);
    return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : fallback;
  }
}
