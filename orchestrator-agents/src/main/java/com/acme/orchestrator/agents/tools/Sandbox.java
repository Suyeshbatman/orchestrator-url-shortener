package com.acme.orchestrator.agents.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Somewhere other than this machine to run an allowed command: the validation build of code that no
 * human has reviewed. {@link CommandTool} has already checked the command against the policies.
 */
public interface Sandbox {

  /**
   * @param args the command line, split on whitespace ({@code mvn …} runs the workspace's wrapper)
   * @param workspace the run's workspace: the only part of this machine the command may see
   */
  CommandTool.Result run(List<String> args, Path workspace, Duration timeout);
}
