package com.acme.shortener.url;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JVM DNS lookup with a hard timeout. {@link InetAddress#getAllByName} cannot be interrupted, so
 * the lookup runs on a virtual thread and the caller stops waiting after {@code timeout}.
 */
public final class DnsHostResolver implements HostResolver, AutoCloseable {

  private final Duration timeout;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public DnsHostResolver(Duration timeout) {
    this.timeout = timeout;
  }

  @Override
  public List<InetAddress> resolve(String host) throws UnknownHostException {
    Future<InetAddress[]> lookup = executor.submit(() -> InetAddress.getAllByName(host));
    try {
      return List.of(lookup.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      lookup.cancel(true);
      throw new UnknownHostException("DNS lookup timed out after " + timeout.toMillis() + "ms");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof UnknownHostException unknown) {
        throw unknown;
      }
      throw new UnknownHostException("DNS lookup failed: " + e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnknownHostException("DNS lookup interrupted");
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
