package com.acme.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReferrersTest {

  @Test
  void keepsOnlyTheLowercasedHost() {
    assertThat(Referrers.host("https://News.Example.com/a/b?user=alice#x"))
        .isEqualTo("news.example.com");
    assertThat(Referrers.host("http://example.org:8080/")).isEqualTo("example.org");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "not a url",
        "android-app://com.google.android.gm/",
        "javascript:alert(1)",
        "https://",
        "/relative/path"
      })
  void rejectsWhatIsNotAnHttpUrlWithAHost(String referer) {
    assertThat(Referrers.host(referer)).isNull();
  }

  @Test
  void rejectsOverlongHosts() {
    assertThat(Referrers.host("https://" + "a".repeat(250) + ".example.com/")).isNull();
  }
}
