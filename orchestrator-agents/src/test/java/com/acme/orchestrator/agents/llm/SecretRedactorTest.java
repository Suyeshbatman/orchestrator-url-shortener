package com.acme.orchestrator.agents.llm;

import static com.acme.orchestrator.agents.llm.SecretRedactor.REDACTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SecretRedactorTest {

  private final SecretRedactor redactor = new SecretRedactor(List.of());

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "aws key AKIAABCDEFGHIJKLMNOP here | aws key [REDACTED] here",
        "token sk-ant-api03-abcdefghijk | token [REDACTED]",
        "ghp_abcdefghijklmnopqrstuvwxyz0123 | [REDACTED]",
        "Authorization: Bearer abcdefghijklmnopqrstuvwxyz | Authorization: Bearer [REDACTED]",
        "SPRING_DATASOURCE_PASSWORD=hunter2hunter2 | SPRING_DATASOURCE_PASSWORD=[REDACTED]",
        "\"apiKey\": \"abcdef123456\" | \"apiKey\": \"[REDACTED]\"",
        "client_secret: s3cr3t-value | client_secret: [REDACTED]",
      })
  void commonSecretFormatsAreRemoved(String input, String expected) {
    assertThat(redactor.redact(input)).isEqualTo(expected);
  }

  @Test
  void privateKeyBlocksAreRemovedWhole() {
    String pem =
        "before\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nIBAAKC\n-----END RSA PRIVATE KEY-----\nafter";

    assertThat(redactor.redact(pem)).isEqualTo("before\n" + REDACTED + "\nafter");
  }

  @Test
  void ordinaryTextIsLeftAlone() {
    String code =
        "public String token() { return tokenizer.next(); } // password policy: min 8 chars";

    assertThat(redactor.redact(code)).isEqualTo(code);
  }

  @Test
  void theValuesOfSecretEnvironmentVariablesAreRemovedWhateverTheirShape() {
    SecretRedactor fromEnv =
        SecretRedactor.fromEnvironment(
            Map.of(
                "POSTGRES_PASSWORD", "correct-horse-battery",
                "SHORTENER_CODE_SECRET", "zyxwvutsrq",
                "GITHUB_TOKEN", "short", // too short to replace verbatim
                "HOME", "/home/alice")); // not a secret

    assertThat(fromEnv.redact("db pw is correct-horse-battery, hmac zyxwvutsrq, home /home/alice"))
        .isEqualTo("db pw is " + REDACTED + ", hmac " + REDACTED + ", home /home/alice");
  }
}
