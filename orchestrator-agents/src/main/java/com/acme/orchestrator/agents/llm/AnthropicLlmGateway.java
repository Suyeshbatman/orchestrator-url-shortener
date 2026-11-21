package com.acme.orchestrator.agents.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.RequestTimeoutException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Live mode: the Anthropic Messages API through the official Java SDK. The SDK's own retries are
 * off; instead a Resilience4j retry (exponential backoff) handles 429s, 5xx and timeouts, inside a
 * circuit breaker that fails fast while the API is down. If the primary model stays unavailable and
 * a fallback model is configured, the call is tried once more on it.
 *
 * <p>Structured requests get the JSON Schema appended to the system prompt; {@link LlmClient}
 * validates the reply.
 */
public final class AnthropicLlmGateway implements LlmGateway, AutoCloseable {

  /** Tuning; the defaults are for production. */
  public record Settings(
      String model,
      Optional<String> fallbackModel,
      Duration timeout,
      int maxAttempts,
      Duration baseDelay) {

    public Settings {
      Objects.requireNonNull(model, "model");
      fallbackModel = Objects.requireNonNull(fallbackModel, "fallbackModel");
      Objects.requireNonNull(timeout, "timeout");
      Objects.requireNonNull(baseDelay, "baseDelay");
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be at least 1");
      }
    }

    /** 60 s timeout, 3 attempts from 1 s backoff. */
    public static Settings of(String model, Optional<String> fallbackModel) {
      return new Settings(model, fallbackModel, Duration.ofSeconds(60), 3, Duration.ofSeconds(1));
    }
  }

  static final String SCHEMA_INSTRUCTION =
      "Respond with only a JSON object that validates against this JSON Schema"
          + " (no prose, no Markdown code fences):\n";

  private final AnthropicClient client;
  private final Settings settings;
  private final CircuitBreaker breaker;

  /**
   * @param baseUrl the API endpoint; empty for the default (set in tests)
   */
  public AnthropicLlmGateway(String apiKey, Optional<String> baseUrl, Settings settings) {
    this(
        AnthropicOkHttpClient.builder()
            .apiKey(Objects.requireNonNull(apiKey, "apiKey"))
            .baseUrl(baseUrl)
            .timeout(settings.timeout())
            .maxRetries(0) // Resilience4j retries
            .build(),
        settings);
  }

  AnthropicLlmGateway(AnthropicClient client, Settings settings) {
    this.client = client;
    this.settings = settings;
    this.breaker =
        CircuitBreaker.of(
            "anthropic",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(AnthropicLlmGateway::retryable)
                .build());
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    try {
      return call(settings.model(), request);
    } catch (LlmUnavailableException primaryDown) {
      if (settings.fallbackModel().isEmpty()) {
        throw primaryDown;
      }
      try {
        return call(settings.fallbackModel().get(), request);
      } catch (LlmUnavailableException fallbackDown) {
        fallbackDown.addSuppressed(primaryDown);
        throw fallbackDown;
      }
    }
  }

  private LlmResponse call(String model, LlmRequest request) {
    MessageCreateParams params = params(model, request);
    Retry retry =
        Retry.of(
            "anthropic-" + model,
            RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(
                    IntervalFunction.ofExponentialRandomBackoff(settings.baseDelay(), 2.0))
                .retryOnException(AnthropicLlmGateway::retryable)
                .build());
    Supplier<Message> send =
        Retry.decorateSupplier(
            retry,
            CircuitBreaker.decorateSupplier(breaker, () -> client.messages().create(params)));
    long started = System.nanoTime();
    Message message;
    try {
      message = send.get();
    } catch (CallNotPermittedException e) {
      throw new LlmUnavailableException("the Anthropic API circuit breaker is open", e);
    } catch (AnthropicException e) {
      if (retryable(e)) {
        throw new LlmUnavailableException(
            model + " unavailable after " + settings.maxAttempts() + " attempts: " + e.getMessage(),
            e);
      }
      String status =
          e instanceof AnthropicServiceException s ? " (HTTP " + s.statusCode() + ")" : "";
      throw new IllegalStateException(
          "the Anthropic API rejected the request" + status + ": " + e.getMessage(), e);
    }
    Duration latency = Duration.ofNanos(System.nanoTime() - started);
    String text =
        message.content().stream()
            .map(ContentBlock::text)
            .flatMap(Optional::stream)
            .map(TextBlock::text)
            .collect(Collectors.joining());
    return new LlmResponse(
        text,
        Optional.empty(),
        message.usage().inputTokens(),
        message.usage().outputTokens(),
        message.model().asString(),
        latency);
  }

  static MessageCreateParams params(String model, LlmRequest request) {
    String system =
        request
            .jsonSchema()
            .map(schema -> request.systemPrompt() + "\n\n" + SCHEMA_INSTRUCTION + schema)
            .orElse(request.systemPrompt());
    MessageCreateParams.Builder params =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxTokens())
            .system(system)
            .addUserMessage(request.userPrompt());
    request
        .temperature()
        .ifPresent(t -> params.putAdditionalBodyProperty("temperature", JsonValue.from(t)));
    return params.build();
  }

  /** 429, 408, 5xx (including 529 overloaded) and network trouble are worth retrying. */
  static boolean retryable(Throwable e) {
    return e instanceof RateLimitException
        || e instanceof InternalServerException
        || e instanceof RequestTimeoutException
        || e instanceof AnthropicIoException
        || e instanceof AnthropicRetryableException
        || (e instanceof AnthropicServiceException s && s.statusCode() >= 500);
  }

  @Override
  public void close() {
    client.close();
  }
}
