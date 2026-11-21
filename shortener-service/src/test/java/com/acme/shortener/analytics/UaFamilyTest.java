package com.acme.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class UaFamilyTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/130.0.0.0 Safari/537.36 | CHROME",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0 | EDGE",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0 | FIREFOX",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7) AppleWebKit/605.1.15 (KHTML, like Gecko)"
            + " Version/18.0 Safari/605.1.15 | SAFARI",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like"
            + " Gecko) CriOS/130.0 Mobile/15E148 Safari/604.1 | CHROME",
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html) | BOT",
        "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; bingbot/2.0)"
            + " Chrome/116.0 Safari/537.36 | BOT",
        "curl/8.9.1 | BOT",
        "SomethingElse/1.0 | OTHER"
      })
  void classifies(String userAgent, UaFamily expected) {
    assertThat(UaFamily.of(userAgent)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void missingIsOther(String userAgent) {
    assertThat(UaFamily.of(userAgent)).isEqualTo(UaFamily.OTHER);
  }

  @org.junit.jupiter.api.Test
  void labelIsCapitalized() {
    assertThat(UaFamily.FIREFOX.label()).isEqualTo("Firefox");
    assertThat(UaFamily.BOT.label()).isEqualTo("Bot");
  }
}
