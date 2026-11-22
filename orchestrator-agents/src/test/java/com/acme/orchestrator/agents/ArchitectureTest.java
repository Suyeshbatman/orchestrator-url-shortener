package com.acme.orchestrator.agents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Agents see a run only through the core's ports ({@code StageContext}, the policy engine, the
 * checkpoint and schema interfaces), never through the engine's internals or its stores, so an
 * agent cannot write events or artifacts behind the coordinator's back.
 */
@AnalyzeClasses(
    packages = "com.acme.orchestrator.agents",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule agentsUseOnlyTheCorePorts =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.acme.orchestrator.core.engine.Coordinator")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.acme.orchestrator.core.engine.Command")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.acme.orchestrator.core.event.EventStore")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.acme.orchestrator.core.event.RunStateProjector")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.acme.orchestrator.core.artifact.ArtifactStore")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleNameStartingWith("InMemory")
          .because("agents reach a run only through StageContext");

  @ArchTest
  static final ArchRule agentsKnowNoAppOrCli =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "com.acme.orchestrator.app..",
              "com.acme.orchestrator.cli..");
}
