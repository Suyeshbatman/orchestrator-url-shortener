package com.acme.orchestrator.cli;

/** Entry point for the demo/operator CLI. Subcommands are implemented in Step 9.2. */
public final class Main {

  private static final String USAGE =
      """
      Usage: orchestrator-cli <command> [args]

      Commands:
        demo                   Run all three scenarios end-to-end (replay mode)
        run-scenario <id>      Run one scenario (greenfield-qr-code | brownfield-link-expiry |
                               ambiguous-abuse-protection)
        verify-audit <runId>   Verify a run's hash-chained audit log
        metrics                Print the reliability metrics summary
      """;

  private Main() {}

  public static void main(String[] args) {
    System.out.print(USAGE);
  }
}
