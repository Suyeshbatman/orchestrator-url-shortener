package com.acme.orchestrator.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The engine is plain Java: no framework, no Git, no HTTP. Adapters (Spring persistence, JGit
 * checkpoints, the LLM client) live in other modules and plug in through the core's ports.
 */
@AnalyzeClasses(
    packages = "com.acme.orchestrator.core",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule coreUsesNoFrameworkGitOrHttp =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "org.eclipse.jgit..",
              "java.net.http..",
              "okhttp3..",
              "com.anthropic..");

  @ArchTest
  static final ArchRule coreKnowsNoOtherModule =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.acme.orchestrator.agents..",
              "com.acme.orchestrator.app..",
              "com.acme.orchestrator.cli..",
              "com.acme.shortener..");
}
