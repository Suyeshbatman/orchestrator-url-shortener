package com.acme.shortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

  private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

  private MockHttpServletResponse run(String path, boolean secure) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setSecure(secure);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void apiResponsesGetTheFullSetAndNoStore() throws Exception {
    MockHttpServletResponse r = run("/api/v1/links/abc", false);

    assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(r.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    assertThat(r.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.CSP);
    assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(r.getHeader("Strict-Transport-Security")).isNull(); // plain HTTP
  }

  @Test
  void redirectsAreNotMarkedNoStore() throws Exception {
    assertThat(run("/abc1234", false).getHeader("Cache-Control"))
        .isNull(); // the controller sets it
  }

  @Test
  void swaggerUiKeepsItsScripts() throws Exception {
    MockHttpServletResponse r = run("/swagger-ui/index.html", false);

    assertThat(r.getHeader("Content-Security-Policy")).isNull();
    assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void hstsOnlyOverHttps() throws Exception {
    assertThat(run("/abc1234", true).getHeader("Strict-Transport-Security"))
        .isEqualTo("max-age=31536000; includeSubDomains");
  }
}
