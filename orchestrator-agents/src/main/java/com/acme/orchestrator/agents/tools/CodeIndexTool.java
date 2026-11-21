package com.acme.orchestrator.agents.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A lightweight map of a Java repository for brownfield reasoning, built with regular expressions
 * (no compiler): source files and packages, public types with their public methods, REST mappings,
 * and Flyway migrations. Small enough to put in a prompt ({@link RepoMap#toMarkdown()}).
 */
public final class CodeIndexTool {

  public record JavaType(String file, String pkg, String kind, String name, List<String> methods) {}

  public record RestMapping(String httpMethod, String path, String file) {}

  public record RepoMap(
      List<String> javaFiles,
      List<String> packages,
      List<JavaType> types,
      List<RestMapping> restMappings,
      List<String> migrations) {

    /** A compact outline for prompts. */
    public String toMarkdown() {
      StringBuilder md = new StringBuilder("# Repository map\n\n## REST endpoints\n");
      restMappings.forEach(r ->
          md.append("- ")
              .append(r.httpMethod())
              .append(' ')
              .append(r.path())
              .append(" (")
              .append(r.file())
              .append(")\n"));
      md.append("\n## Database migrations\n");
      migrations.forEach(m -> md.append("- ").append(m).append('\n'));
      md.append("\n## Public types\n");
      for (JavaType t : types) {
        md.append("- ").append(t.kind()).append(' ').append(t.pkg()).append('.').append(t.name());
        if (!t.methods().isEmpty()) {
          md.append(": ").append(String.join(", ", t.methods()));
        }
        md.append('\n');
      }
      return md.toString();
    }
  }

  private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
  private static final Pattern TYPE =
      Pattern.compile(
          "(?m)^public\\s+(?:(?:final|abstract|sealed|non-sealed|static)\\s+)*"
              + "(class|interface|record|enum|@interface)\\s+(\\w+)");
  private static final Pattern METHOD =
      Pattern.compile(
          "(?m)^\\s+public\\s+(?:(?:static|final|default|synchronized|abstract)\\s+)*"
              + "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],? ]+?\\s+(\\w+)\\s*\\(");
  private static final Pattern CLASS_MAPPING =
      Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\"");
  private static final Pattern MAPPING =
      Pattern.compile(
          "@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?(?:\\{\\s*)?\"([^\"]*)\")?");
  private static final Pattern MIGRATION = Pattern.compile("V\\d+(?:_\\d+)*__\\w+\\.sql");

  private final Path root;

  public CodeIndexTool(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public RepoMap index() {
    List<String> javaFiles = new ArrayList<>();
    TreeSet<String> packages = new TreeSet<>();
    List<JavaType> types = new ArrayList<>();
    List<RestMapping> mappings = new ArrayList<>();
    List<String> migrations = new ArrayList<>();
    for (Path file : files()) {
      String rel = root.relativize(file).toString().replace('\\', '/');
      String name = file.getFileName().toString();
      if (name.endsWith(".sql")
          && rel.contains("db/migration/")
          && MIGRATION.matcher(name).matches()) {
        migrations.add(rel);
      }
      if (!name.endsWith(".java")) {
        continue;
      }
      javaFiles.add(rel);
      String src = read(file);
      Matcher pkg = PACKAGE.matcher(src);
      String pkgName = pkg.find() ? pkg.group(1) : "";
      packages.add(pkgName);
      Matcher type = TYPE.matcher(src);
      if (type.find()) {
        List<String> methods = new ArrayList<>();
        Matcher m = METHOD.matcher(src);
        while (m.find()) {
          if (!m.group(1).equals(type.group(2))) { // constructors aren't methods
            methods.add(m.group(1));
          }
        }
        types.add(new JavaType(rel, pkgName, type.group(1), type.group(2), List.copyOf(methods)));
      }
      Matcher base = CLASS_MAPPING.matcher(src);
      String prefix = base.find() ? base.group(1) : "";
      Matcher mapping = MAPPING.matcher(src);
      while (mapping.find()) {
        String path = mapping.group(2) == null ? "" : mapping.group(2);
        mappings.add(
            new RestMapping(
                mapping.group(1).toUpperCase(java.util.Locale.ROOT), join(prefix, path), rel));
      }
    }
    migrations.sort(null);
    return new RepoMap(
        List.copyOf(javaFiles),
        List.copyOf(packages),
        List.copyOf(types),
        List.copyOf(mappings),
        List.copyOf(migrations));
  }

  private List<Path> files() {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> {
            String rel = root.relativize(p).toString().replace('\\', '/');
            return !rel.startsWith(".git/")
                && !rel.contains("/target/")
                && !rel.startsWith("target/")
                && !rel.contains("/node_modules/");
          })
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot index " + root, e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private static String join(String prefix, String path) {
    String joined = (prefix + "/" + path).replaceAll("/+", "/");
    return joined.length() > 1 && joined.endsWith("/")
        ? joined.substring(0, joined.length() - 1)
        : joined;
  }
}
