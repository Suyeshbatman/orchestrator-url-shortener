package com.acme.shortener.url;

import com.acme.shortener.url.InvalidUrlException.Reason;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates destination URLs at creation time (NFR-3, DESIGN_RATIONALE §3.6).
 *
 * <p>Blocking private ranges is defence in depth against SSRF if anything server-side ever fetches
 * the URL. Known limitation: DNS rebinding (public at creation, private later); a fetcher would
 * have to resolve and pin the IP itself. Hosts that do not resolve are rejected (fail closed).
 */
public final class UrlValidator {

  private static final Pattern DOTTED_QUAD =
      Pattern.compile(
          "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
  // Decimal (2130706433), short (127.1), octal (0177.0.0.1) or hex (0x7f.1) IPv4 spellings.
  private static final Pattern NUMERIC_HOST =
      Pattern.compile("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*$");

  private final int maxLength;
  private final Set<String> allowedSchemes;
  private final boolean blockPrivateNetworks;
  private final String selfHost;
  private final HostResolver resolver;

  public UrlValidator(
      int maxLength,
      Set<String> allowedSchemes,
      boolean blockPrivateNetworks,
      String baseUrl,
      HostResolver resolver) {
    this.maxLength = maxLength;
    this.allowedSchemes =
        allowedSchemes.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    this.blockPrivateNetworks = blockPrivateNetworks;
    this.selfHost = normalizeHost(URI.create(baseUrl).getHost());
    this.resolver = resolver;
  }

  /**
   * Returns the parsed URL (surrounding whitespace stripped) or throws {@link InvalidUrlException}.
   */
  public URI validate(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new InvalidUrlException(Reason.MALFORMED, "url is required");
    }
    String url = rawUrl.strip();
    if (url.length() > maxLength) {
      throw new InvalidUrlException(
          Reason.TOO_LONG, "url must be at most " + maxLength + " characters");
    }

    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new InvalidUrlException(Reason.MALFORMED, "url is not a valid URI");
    }

    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!allowedSchemes.contains(scheme)) {
      throw new InvalidUrlException(
          Reason.SCHEME_NOT_ALLOWED, "scheme must be one of " + allowedSchemes);
    }
    if (uri.getRawUserInfo() != null) {
      throw new InvalidUrlException(
          Reason.USERINFO_NOT_ALLOWED, "credentials in the URL (user:pass@host) are not allowed");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new InvalidUrlException(Reason.MISSING_HOST, "url must have a valid host");
    }

    String host = normalizeHost(uri.getHost());
    if (host.equals(selfHost)) {
      throw new InvalidUrlException(Reason.SELF_REFERENCE, "url must not point at this service");
    }
    if (blockPrivateNetworks) {
      checkPublic(host);
    }
    return uri;
  }

  private void checkPublic(String host) {
    boolean ipv6Literal = host.startsWith("[");
    if (!ipv6Literal
        && NUMERIC_HOST.matcher(host).matches()
        && !DOTTED_QUAD.matcher(host).matches()) {
      throw new InvalidUrlException(
          Reason.NON_CANONICAL_IP, "IP addresses must be written as a.b.c.d");
    }

    List<InetAddress> addresses;
    try {
      if (ipv6Literal || DOTTED_QUAD.matcher(host).matches()) {
        // A literal: InetAddress parses it without any DNS lookup.
        addresses = List.of(InetAddress.getByName(host));
      } else {
        addresses = resolver.resolve(host);
      }
    } catch (UnknownHostException e) {
      throw new InvalidUrlException(Reason.UNRESOLVABLE_HOST, "host does not resolve");
    }

    for (InetAddress address : addresses) {
      if (isNonPublic(address)) {
        throw new InvalidUrlException(
            Reason.PRIVATE_ADDRESS, "url must not point at a private or local network address");
      }
    }
  }

  static boolean isNonPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = b[0] & 0xff;
      int second = b[1] & 0xff;
      return first == 0 // "this network" 0.0.0.0/8
          || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT 100.64.0.0/10
          || first >= 240; // reserved 240.0.0.0/4 and broadcast
    }
    if (address instanceof Inet6Address) {
      if ((b[0] & 0xfe) == 0xfc) {
        return true; // unique-local fc00::/7
      }
      // IPv4 embedded in IPv6 (::a.b.c.d compatible form, 64:ff9b::/96 NAT64): check the IPv4 part.
      if (isZero(b, 0, 12) || isNat64Prefix(b)) {
        return isNonPublic(embeddedIpv4(b));
      }
    }
    return false;
  }

  private static boolean isZero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean isNat64Prefix(byte[] b) {
    return b[0] == 0x00
        && b[1] == 0x64
        && (b[2] & 0xff) == 0xff
        && (b[3] & 0xff) == 0x9b
        && isZero(b, 4, 12);
  }

  private static InetAddress embeddedIpv4(byte[] b) {
    try {
      return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e); // impossible: 4 bytes is always a valid IPv4 address
    }
  }

  private static String normalizeHost(String host) {
    String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
    return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
  }
}
