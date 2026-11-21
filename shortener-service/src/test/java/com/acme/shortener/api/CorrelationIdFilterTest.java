package com.acme.shortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();
  private final AtomicReference<String> seenInChain = new AtomicReference<>();
  private final MockFilterChain chain =
      new MockFilterChain() {
        @Override
        public void doFilter(
            jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
          seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        }
      };

  @Test
  void keepsAValidClientIdAndEchoesIt() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Correlation-Id", "req-42.abc_DEF");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(seenInChain).hasValue("req-42.abc_DEF");
    assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("req-42.abc_DEF");
  }

  @Test
  void generatesAUuidWhenMissing() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest(), response, chain);

    assertThat(response.getHeader("X-Correlation-Id"))
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        .isEqualTo(seenInChain.get());
  }

  /** It is written to logs, so anything that could forge or break a log line is replaced. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "has space",
        "line\nbreak",
        "{\"json\":1}",
        "",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" /* 65 chars */
      })
  void replacesUnsafeIds(String unsafe) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Correlation-Id", unsafe);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Correlation-Id")).isNotEqualTo(unsafe).hasSize(36);
  }

  @Test
  void clearsTheMdcAfterTheRequest() throws Exception {
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

    assertThat(seenInChain.get()).isNotNull();
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }
}
