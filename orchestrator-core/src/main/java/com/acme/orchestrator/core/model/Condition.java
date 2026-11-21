package com.acme.orchestrator.core.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stage condition such as {@code run.mode == 'BROWNFIELD'}. Deliberately tiny: one run field,
 * {@code ==} or {@code !=}, one quoted constant. No general expression language, so a workflow file
 * can't run code. Parsed (and so validated) when the workflow is loaded.
 */
public record Condition(Field field, Operator operator, String value) {

  public enum Field {
    MODE("run.mode"),
    AUTONOMY_LEVEL("run.autonomyLevel");

    private final String path;

    Field(String path) {
      this.path = path;
    }

    String read(Run run) {
      return switch (this) {
        case MODE -> run.mode().name();
        case AUTONOMY_LEVEL -> run.autonomyLevel().name();
      };
    }

    boolean accepts(String constant) {
      Class<? extends Enum<?>> type = this == MODE ? RunMode.class : AutonomyLevel.class;
      return Arrays.stream(type.getEnumConstants()).anyMatch(c -> c.name().equals(constant));
    }
  }

  public enum Operator {
    EQUALS,
    NOT_EQUALS
  }

  private static final Pattern SYNTAX =
      Pattern.compile("\\s*(run\\.[A-Za-z]+)\\s*(==|!=)\\s*'([A-Za-z0-9_]+)'\\s*");

  /**
   * @throws IllegalArgumentException for anything but {@code run.mode|run.autonomyLevel ==|!=
   *     'CONSTANT'} with a constant that exists
   */
  public static Condition parse(String expression) {
    Matcher m = SYNTAX.matcher(expression == null ? "" : expression);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "condition must look like run.mode == 'BROWNFIELD' (fields: run.mode,"
              + " run.autonomyLevel; operators: ==, !=): "
              + expression);
    }
    Field field =
        Arrays.stream(Field.values())
            .filter(f -> f.path.equals(m.group(1)))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("unknown condition field: " + m.group(1)));
    String value = m.group(3).toUpperCase(Locale.ROOT);
    if (!field.accepts(value)) {
      throw new IllegalArgumentException(
          "'" + m.group(3) + "' is not a valid value for " + field.path);
    }
    return new Condition(
        field, m.group(2).equals("==") ? Operator.EQUALS : Operator.NOT_EQUALS, value);
  }

  public boolean test(Run run) {
    boolean equal = field.read(run).equals(value);
    return operator == Operator.EQUALS ? equal : !equal;
  }
}
