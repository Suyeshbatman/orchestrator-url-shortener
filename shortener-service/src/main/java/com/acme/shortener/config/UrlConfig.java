package com.acme.shortener.config;

import com.acme.shortener.url.DnsHostResolver;
import com.acme.shortener.url.HostResolver;
import com.acme.shortener.url.UrlValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class UrlConfig {

  @Bean
  DnsHostResolver hostResolver(ShortenerProperties props) {
    return new DnsHostResolver(props.url().dnsTimeout());
  }

  @Bean
  UrlValidator urlValidator(ShortenerProperties props, HostResolver hostResolver) {
    ShortenerProperties.Url url = props.url();
    return new UrlValidator(
        url.maxLength(),
        url.allowedSchemes(),
        url.blockPrivateNetworks(),
        props.baseUrl(),
        hostResolver);
  }
}
