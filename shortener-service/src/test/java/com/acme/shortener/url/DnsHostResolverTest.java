package com.acme.shortener.url;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DnsHostResolverTest {

  @Test
  void resolvesLocalhostWithoutNetwork() throws UnknownHostException {
    try (DnsHostResolver resolver = new DnsHostResolver(Duration.ofMillis(500))) {
      assertThat(resolver.resolve("localhost")).isNotEmpty().allMatch(a -> a.isLoopbackAddress());
    }
  }

  @Test
  void resolvesIpLiteralsWithoutNetwork() throws UnknownHostException {
    try (DnsHostResolver resolver = new DnsHostResolver(Duration.ofMillis(500))) {
      assertThat(resolver.resolve("93.184.215.14")).hasSize(1);
    }
  }
}
