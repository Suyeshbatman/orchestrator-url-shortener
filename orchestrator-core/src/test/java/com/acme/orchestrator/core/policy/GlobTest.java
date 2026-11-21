package com.acme.orchestrator.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobTest {

  @ParameterizedTest
  @CsvSource({
    "**/pom.xml, pom.xml, true",
    "**/pom.xml, shortener-service/pom.xml, true",
    "**/pom.xml, a/b/c/pom.xml, true",
    "**/pom.xml, a/notpom.xml, false",
    "shortener-service/src/**, shortener-service/src/main/X.java, true",
    "shortener-service/src/**, shortener-service/srcx/X.java, false",
    "shortener-service/src/**, shortener-service/pom.xml, false",
    "orchestrator-*/**, orchestrator-core/pom.xml, true",
    "orchestrator-*/**, orchestrator/pom.xml, false",
    "**/db/migration/**, shortener-service/src/main/resources/db/migration/V1__x.sql, true",
    "**/db/migration/**, db/migration/V1.sql, true",
    "**/config/Security*.java, a/config/SecurityConfig.java, true",
    "**/config/Security*.java, a/config/sub/SecurityConfig.java, false",
    "config/policies.yml, config/policies.yml, true",
    "config/policies.yml, config/policiesXyml, false",
    "a?c, abc, true",
    "a?c, a/c, false"
  })
  void matches(String glob, String path, boolean expected) {
    assertThat(Glob.of(glob).matches(path)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"''", "'  '"})
  void blankGlobsAreRejected(String glob) {
    assertThatThrownBy(() -> Glob.of(glob)).isInstanceOf(IllegalArgumentException.class);
  }
}
