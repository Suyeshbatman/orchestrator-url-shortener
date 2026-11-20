package com.acme.shortener.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.shortener.url.InvalidUrlException.Reason;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

  private final FakeResolver resolver = new FakeResolver();
  private final UrlValidator validator =
      new UrlValidator(2048, Set.of("http", "https"), true, "http://sho.rt:8085", resolver);

  {
    resolver.map("example.com", "93.184.215.14");
    resolver.map("dual.example.com", "93.184.215.14", "2606:2800:21f:cb07:6820:80da:af6b:8b2c");
    resolver.map("internal.example.com", "10.0.0.5");
    resolver.map("sneaky.example.com", "93.184.215.14", "192.168.1.10"); // one private answer
    resolver.map("localhost", "127.0.0.1");
    resolver.map("metadata.example.com", "169.254.169.254");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.com",
        "http://example.com/path?q=1#frag",
        "HTTPS://EXAMPLE.COM/x",
        "https://dual.example.com:8443/a",
        "  https://example.com/trimmed  ",
        "https://93.184.215.14/literal"
      })
  void acceptsPublicHttpUrls(String url) {
    assertThat(validator.validate(url).getHost()).isNotBlank();
  }

  @Test
  void rejectsUrlLongerThanMax() {
    String url = "https://example.com/" + "a".repeat(2049 - "https://example.com/".length());
    assertThat(url).hasSize(2049);

    assertReason(url, Reason.TOO_LONG);
  }

  @Test
  void acceptsUrlOfExactlyMaxLength() {
    String url = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());

    assertThat(validator.validate(url)).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "data:text/plain,hello",
        "ftp://example.com/file",
        "file:///etc/passwd",
        "//example.com/no-scheme",
        "example.com"
      })
  void rejectsDisallowedSchemes(String url) {
    assertReason(url, Reason.SCHEME_NOT_ALLOWED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://user:pw@x.com", "https://paypal.com@evil.com/login"})
  void rejectsUserInfo(String url) {
    assertReason(url, Reason.USERINFO_NOT_ALLOWED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://exa mple.com",
        "http://example.com/<>",
        "http://[::1",
        "https://",
        "data:text/html,<script>x</script>"
      })
  void rejectsMalformed(String url) {
    assertReason(url, Reason.MALFORMED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http:///path-only",
        "http://under_score.example.com",
        "http://127.1",
        "http://0x7f.0.0.1"
      })
  void rejectsMissingHost(String url) {
    assertReason(url, Reason.MISSING_HOST);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void rejectsBlank(String url) {
    assertReason(url, Reason.MALFORMED);
  }

  @Test
  void rejectsNull() {
    assertReason(null, Reason.MALFORMED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1",
        "http://127.0.0.1:8080/admin",
        "http://[::1]",
        "http://[::1]:8080/",
        "http://0.0.0.0",
        "http://10.1.2.3",
        "http://172.16.0.1",
        "http://192.168.0.1",
        "http://169.254.169.254/latest/meta-data",
        "http://100.64.0.1",
        "http://224.0.0.1",
        "http://255.255.255.255",
        "http://[fe80::1]",
        "http://[fc00::1]",
        "http://[fd12:3456:789a::1]",
        "http://[ff02::1]",
        "http://[::ffff:127.0.0.1]",
        "http://[::ffff:10.0.0.1]",
        "http://[::127.0.0.1]",
        "http://[64:ff9b::10.0.0.1]",
        "http://localhost",
        "http://internal.example.com",
        "http://sneaky.example.com",
        "http://metadata.example.com"
      })
  void rejectsPrivateAndLocalAddresses(String url) {
    assertReason(url, Reason.PRIVATE_ADDRESS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://2130706433", "http://0177.0.0.1", "http://0x7f000001"})
  void rejectsNonCanonicalIpSpellings(String url) {
    assertReason(url, Reason.NON_CANONICAL_IP);
  }

  @Test
  void rejectsUnresolvableHost() {
    assertReason("https://does-not-exist.example", Reason.UNRESOLVABLE_HOST);
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://sho.rt:8085/abc", "https://SHO.RT/abc", "http://sho.rt./x"})
  void rejectsOwnBaseUrlHost(String url) {
    assertReason(url, Reason.SELF_REFERENCE);
  }

  @Test
  void literalIpsAreCheckedWithoutDns() {
    validator.validate("https://93.184.215.14/");
    assertThatThrownBy(() -> validator.validate("http://[::1]"))
        .isInstanceOf(InvalidUrlException.class);

    assertThat(resolver.lookups).isEmpty();
  }

  @Test
  void privateNetworksAllowedWhenBlockingDisabled() {
    UrlValidator lenient =
        new UrlValidator(2048, Set.of("http", "https"), false, "http://sho.rt", resolver);

    assertThat(lenient.validate("http://10.0.0.5/").getHost()).isEqualTo("10.0.0.5");
    assertThat(resolver.lookups).isEmpty();
  }

  private void assertReason(String url, Reason reason) {
    assertThatThrownBy(() -> validator.validate(url))
        .isInstanceOfSatisfying(
            InvalidUrlException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  /** In-memory DNS. Unknown hosts fail like NXDOMAIN. */
  static final class FakeResolver implements HostResolver {
    private final Map<String, List<InetAddress>> records = new HashMap<>();
    final List<String> lookups = new ArrayList<>();

    void map(String host, String... ips) {
      List<InetAddress> addresses = new ArrayList<>();
      for (String ip : ips) {
        try {
          addresses.add(InetAddress.getByName(ip)); // literals: no real DNS
        } catch (UnknownHostException e) {
          throw new IllegalArgumentException(e);
        }
      }
      records.put(host, addresses);
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
      lookups.add(host);
      List<InetAddress> addresses = records.get(host);
      if (addresses == null) {
        throw new UnknownHostException(host);
      }
      return addresses;
    }
  }
}
