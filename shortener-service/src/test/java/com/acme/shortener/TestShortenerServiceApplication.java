package com.acme.shortener;

import org.springframework.boot.SpringApplication;

public class TestShortenerServiceApplication {

  public static void main(String[] args) {
    SpringApplication.from(ShortenerServiceApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
