package com.acme.orchestrator.cli;

import com.acme.orchestrator.agents.llm.LlmGateway;
import com.acme.orchestrator.agents.llm.LlmRequest;
import com.acme.orchestrator.agents.llm.LlmResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hand-written replies, for writing fixtures without a model and for forcing a reply the model
 * would not give (e.g. the deliberately failing first implementation in the brownfield scenario).
 * The n-th call of an agent is answered from {@code replies/<Agent>-<n>.json} when that file
 * exists; otherwise the delegate (the live model) answers. Without a delegate, the exact prompt is
 * written to {@code replies/pending/<Agent>-<n>.prompt.md} so a reply can be written for it, and
 * the run stops.
 *
 * <p>Calls of one agent are sequential (an agent's stage runs one attempt at a time), so the
 * numbering is the same on every run.
 */
public final class ScriptedLlmGateway implements LlmGateway {

  private final Path repliesDir;
  private final Optional<LlmGateway> delegate;
  private final Map<String, Integer> calls = new ConcurrentHashMap<>();

  public ScriptedLlmGateway(Path repliesDir, Optional<LlmGateway> delegate) {
    this.repliesDir = repliesDir;
    this.delegate = delegate;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    int n = calls.merge(request.agent(), 1, Integer::sum);
    String name = request.agent() + "-" + n;
    Path reply = repliesDir.resolve(name + ".json");
    if (Files.isRegularFile(reply)) {
      return new LlmResponse(
          read(reply).strip(), Optional.empty(), 0, 0, "hand-written", Duration.ZERO);
    }
    if (delegate.isPresent()) {
      return delegate.get().complete(request);
    }
    Path pending = repliesDir.resolve("pending").resolve(name + ".prompt.md");
    write(
        pending,
        "# "
            + name
            + "\n\n## System\n"
            + request.systemPrompt()
            + "\n\n## User\n"
            + request.userPrompt()
            + request.jsonSchema().map(s -> "\n\n## JSON Schema\n" + s).orElse("")
            + "\n");
    throw new IllegalStateException(
        "no hand-written reply "
            + reply.getFileName()
            + ": the prompt is in "
            + pending
            + "; write the reply and run again");
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
