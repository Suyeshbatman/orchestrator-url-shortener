package com.acme.shortener.config;

import com.acme.shortener.auth.ApiKeyAuthInterceptor;
import com.acme.shortener.auth.ApiKeyRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

  private final ApiKeyRegistry apiKeys;

  WebConfig(ShortenerProperties properties) {
    this.apiKeys = ApiKeyRegistry.parse(properties.apiKeys());
    if (apiKeys.size() == 0) {
      log.warn("No API keys configured (SHORTENER_API_KEYS); every /api request will get 401");
    }
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new ApiKeyAuthInterceptor(apiKeys)).addPathPatterns("/api/**");
  }

  @Bean
  OpenAPI shortenerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("URL Shortener API")
                .version("v1")
                .description("Short links with analytics. Errors are RFC 9457 Problem Details."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyAuthInterceptor.HEADER)));
  }
}
