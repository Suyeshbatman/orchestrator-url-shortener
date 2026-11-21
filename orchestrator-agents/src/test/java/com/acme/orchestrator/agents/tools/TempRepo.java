package com.acme.orchestrator.agents.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;

/** A throwaway source repository with one commit on {@code main}. */
public final class TempRepo {

  public static final Map<String, String> FILES =
      Map.of(
          "README.md", "# demo\n",
          "shortener-service/src/main/java/com/acme/shortener/Link.java",
              "package com.acme.shortener;\n\npublic record Link(String code) {}\n",
          "shortener-service/src/main/resources/db/migration/V1__init.sql",
              "create table link();\n",
          ".gitignore", "target/\n");

  private TempRepo() {}

  public static Path create(Path dir) throws Exception {
    Path repo = dir.resolve("source");
    try (Git git = Git.init().setInitialBranch("main").setDirectory(repo.toFile()).call()) {
      for (Map.Entry<String, String> f : FILES.entrySet()) {
        Path file = repo.resolve(f.getKey());
        Files.createDirectories(file.getParent());
        Files.writeString(file, f.getValue());
      }
      git.add().addFilepattern(".").call();
      git.commit().setMessage("initial").setSign(false).setAuthor("dev", "dev@example.com").call();
    }
    return repo;
  }
}
