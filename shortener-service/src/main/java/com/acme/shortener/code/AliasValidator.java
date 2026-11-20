package com.acme.shortener.code;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates custom aliases. The uniqueness check here gives a friendly error; the partial unique
 * index on lower(code) is the real guard against races.
 */
public final class AliasValidator {

  private final int minLength;
  private final int maxLength;
  private final Pattern pattern;
  private final Set<String> reserved;
  private final Predicate<String> existsIgnoreCase;

  public AliasValidator(
      int minLength,
      int maxLength,
      String pattern,
      Set<String> reserved,
      Predicate<String> existsIgnoreCase) {
    this.minLength = minLength;
    this.maxLength = maxLength;
    this.pattern = Pattern.compile(pattern);
    this.reserved =
        reserved.stream().map(r -> r.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    this.existsIgnoreCase = existsIgnoreCase;
  }

  public void validate(String alias) {
    if (alias == null || alias.length() < minLength || alias.length() > maxLength) {
      throw new InvalidAliasException(
          "alias must be " + minLength + "-" + maxLength + " characters");
    }
    if (!pattern.matcher(alias).matches()) {
      throw new InvalidAliasException("alias may only contain letters, digits, '_' and '-'");
    }
    if (reserved.contains(alias.toLowerCase(Locale.ROOT))) {
      throw new InvalidAliasException("alias '" + alias + "' is reserved");
    }
    if (existsIgnoreCase.test(alias)) {
      throw new AliasTakenException(alias);
    }
  }
}
