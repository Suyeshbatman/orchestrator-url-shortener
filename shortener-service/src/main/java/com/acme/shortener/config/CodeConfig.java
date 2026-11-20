package com.acme.shortener.config;

import com.acme.shortener.code.AliasValidator;
import com.acme.shortener.code.CodeGenerator;
import com.acme.shortener.code.FeistelPermutation;
import com.acme.shortener.link.LinkRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortenerProperties.class)
class CodeConfig {

  @Bean
  CodeGenerator codeGenerator(ShortenerProperties props, LinkRepository links) {
    ShortenerProperties.Code code = props.code();
    FeistelPermutation permutation =
        FeistelPermutation.forCodeLength(code.secret(), code.length(), code.feistelRounds());
    return new CodeGenerator(links::nextId, permutation, code.length());
  }

  @Bean
  AliasValidator aliasValidator(ShortenerProperties props, LinkRepository links) {
    ShortenerProperties.Alias alias = props.alias();
    return new AliasValidator(
        alias.min(), alias.max(), alias.pattern(), alias.reserved(), links::existsByCodeIgnoreCase);
  }
}
