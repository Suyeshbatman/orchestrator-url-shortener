package com.acme.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.app.api.ApiKeys.Role;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.model.Hashing;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

  private static String entry(String name, String key) {
    return name + ":" + Hashing.sha256Hex(key);
  }

  @Test
  void keysCarryTheRolesOfEveryListTheirNameIsIn() {
    ApiKeys keys =
        new ApiKeys(
            entry("bob", "b-key") + ", " + entry("alice", "a-key"),
            entry("alice", "a-key")
                + ","
                + entry("carol", "c-key").toUpperCase().replace("CAROL", "carol"));

    assertThat(keys.authenticate("b-key").orElseThrow().roles()).containsExactly(Role.OPERATOR);
    assertThat(keys.authenticate("a-key").orElseThrow().roles())
        .isEqualTo(Set.of(Role.OPERATOR, Role.APPROVER));
    assertThat(keys.authenticate("c-key").orElseThrow().roles()).containsExactly(Role.APPROVER);
    assertThat(keys.authenticate("a-key").orElseThrow().actor()).isEqualTo(Actor.human("alice"));
    assertThat(keys.authenticate("wrong")).isEmpty();
    assertThat(keys.authenticate("")).isEmpty();
    assertThat(keys.authenticate(null)).isEmpty();
  }

  @Test
  void malformedConfigFailsAtStartupWithoutPrintingTheHash() {
    assertThatThrownBy(() -> new ApiKeys("alice:sha256-of-approver-key", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("API keys must be name:sha256hex (64 hex characters), got 'alice:…'");
    assertThatThrownBy(() -> new ApiKeys(entry("bob", "k"), entry("eve", "k")))
        .hasMessage("one key is configured for two names: bob");
    assertThat(new ApiKeys("", " ").isEmpty()).isTrue();
  }
}
