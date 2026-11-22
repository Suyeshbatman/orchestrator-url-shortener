package com.acme.shortener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/** Controllers stay thin: storage goes through a service, never straight from the web layer. */
@AnalyzeClasses(
    packages = "com.acme.shortener",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule controllersDoNotAccessStorage =
      noClasses()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .dependOnClassesThat()
          .areAssignableTo(org.springframework.data.repository.Repository.class)
          .orShould()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .orShould()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.jdbc..", "org.springframework.data.redis.core..")
          .because("controllers call services; services own the repositories and caches");
}
