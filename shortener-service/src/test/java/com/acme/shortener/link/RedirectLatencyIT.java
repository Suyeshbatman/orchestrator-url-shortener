package com.acme.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.shortener.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * A small load test of the hot path: redirects of cached links over real HTTP (embedded server,
 * real Postgres and Redis). It warms the cache, then measures one client (the latency of a
 * redirect) and {@value #CLIENTS} concurrent clients (throughput), and reports p50 / p95 / p99 to
 * {@code target/benchmark/redirect-latency.md}. The assertion is a loose ceiling so that a slow CI
 * machine doesn't fail the build; the numbers recorded in docs/testing.md are the real result.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RedirectLatencyIT {

  static final int LINKS = 100;
  static final int WARMUP = 2_000;
  static final int REQUESTS = 20_000;
  static final int CLIENTS = 32;

  @LocalServerPort int port;
  @Autowired LinkRepository links;

  @Test
  void cachedRedirectsStayFast() throws Exception {
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < LINKS; i++) {
      String code = "perf" + i + "x" + Long.toString(System.nanoTime(), 36).substring(4);
      links.save(
          Link.newAlias(
              links.nextId(), code, "https://example.com/" + i, "tester", Clock.systemUTC()));
      codes.add(code);
    }
    HttpClient http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    run(http, codes, WARMUP, CLIENTS); // fills L1 and L2, and warms the JIT
    StringBuilder report =
        new StringBuilder("| clients | requests | p50 ms | p95 ms | p99 ms | max ms | req/s |\n")
            .append("|---|---|---|---|---|---|---|\n");
    double singleClientP99 = 0;
    // One client: the latency of a redirect. Many: throughput, where waiting in line dominates.
    for (int clients : new int[] {1, CLIENTS}) {
      int total = clients == 1 ? REQUESTS / 4 : REQUESTS;
      long started = System.nanoTime();
      long[] nanos = run(http, codes, total, clients);
      double seconds = (System.nanoTime() - started) / 1e9;
      Arrays.sort(nanos);
      report.append(
          String.format(
              Locale.ROOT,
              "| %d | %d | %.2f | %.2f | %.2f | %.2f | %.0f |%n",
              clients,
              total,
              millis(nanos, 0.50),
              millis(nanos, 0.95),
              millis(nanos, 0.99),
              nanos[nanos.length - 1] / 1e6,
              total / seconds));
      if (clients == 1) {
        singleClientP99 = millis(nanos, 0.99);
      }
    }
    Path out = Path.of("target", "benchmark", "redirect-latency.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report);
    System.out.print(report);

    assertThat(singleClientP99)
        .as("p99 of a cached redirect, one client, in ms (loose ceiling)")
        .isLessThan(100.0);
  }

  /** Sends {@code total} redirects round-robin over the codes; returns each latency in ns. */
  private long[] run(HttpClient http, List<String> codes, int total, int concurrency)
      throws Exception {
    long[] nanos = new long[total];
    try (ExecutorService clients = Executors.newFixedThreadPool(concurrency)) {
      List<Future<?>> done = new ArrayList<>();
      for (int c = 0; c < concurrency; c++) {
        int client = c;
        done.add(
            clients.submit(() -> {
              for (int i = client; i < total; i += concurrency) {
                URI uri =
                    URI.create("http://localhost:" + port + "/" + codes.get(i % codes.size()));
                long t0 = System.nanoTime();
                HttpResponse<Void> response =
                    http.send(
                        HttpRequest.newBuilder(uri).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                nanos[i] = System.nanoTime() - t0;
                if (response.statusCode() != 302) {
                  throw new IllegalStateException(uri + " answered " + response.statusCode());
                }
              }
              return null;
            }));
      }
      for (Future<?> f : done) {
        f.get();
      }
    }
    return nanos;
  }

  private static double millis(long[] sorted, double quantile) {
    int i = (int) Math.ceil(quantile * sorted.length) - 1;
    return sorted[Math.max(0, i)] / 1e6;
  }
}
