package com.acme.orchestrator.cli;

import com.acme.orchestrator.cli.ScenarioRunner.LlmMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Entry point for the demo/operator CLI. */
public final class Main {

  private static final String USAGE =
      """
      Usage: orchestrator-cli <command> [args]

      Commands:
        run-scenario <id> [--llm replay|record|handwritten|live] [--timeout 60m]
                               Run one scenario end to end in this process (scripted humans,
                               real agents, real Git and Maven). Default --llm replay.
                                 record       call the model and save scenarios/<id>/replay/
                                              (needs ANTHROPIC_API_KEY; replies/ take precedence)
                                 handwritten  only replies/<Agent>-<n>.json, no API key; a missing
                                              reply's prompt is written to replies/pending/
        record-fixtures <id>   Same as run-scenario <id> --llm handwritten
        demo                   Run all three scenarios end-to-end (replay mode)   [Step 9.2]
        verify-audit <runId>   Verify a run's hash-chained audit log               [Step 9.2]
        metrics                Print the reliability metrics summary               [Step 9.2]
      """;

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
        Duration timeout =
            Duration.parse("PT" + option(args, "--timeout", "60m").toUpperCase(Locale.ROOT));
        Environment env = Environment.discover(Path.of(""), System.getenv());
        ScenarioRunner.Outcome outcome =
            new ScenarioRunner(env, System.out).run(args.get(1), mode, timeout);
        return outcome.expected() && outcome.audit().valid() ? 0 : 1;
      }
      default -> {
        System.out.print(USAGE);
        return 2;
      }
    }
  }

  private static String option(List<String> args, String name, String fallback) {
    int i = args.indexOf(name);
    return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : fallback;
  }
}
