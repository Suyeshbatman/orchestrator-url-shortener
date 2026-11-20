package com.acme.orchestrator.app;

import org.springframework.boot.SpringApplication;

public class TestOrchestratorAppApplication {

  public static void main(String[] args) {
    SpringApplication.from(OrchestratorAppApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
