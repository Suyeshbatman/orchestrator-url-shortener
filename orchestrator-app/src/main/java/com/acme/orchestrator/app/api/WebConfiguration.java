package com.acme.orchestrator.app.api;

import com.acme.orchestrator.app.run.OrchestratorProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

  private final ApiKeys keys;

  WebConfiguration(OrchestratorProperties props) {
    this.keys = new ApiKeys(props.operatorKeys(), props.approverKeys());
  }

  @Bean
  ApiKeys apiKeys() {
    return keys;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new ApiKeyInterceptor(keys)).addPathPatterns("/api/**");
  }

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Agentic SDLC Orchestrator")
                .version("v1")
                .description(
                    "Runs, approvals, artifacts and the audit log. Every call needs X-API-Key;"
                        + " OPERATOR keys steer runs, APPROVER keys decide approvals."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyInterceptor.HEADER)))
        .addSecurityItem(new SecurityRequirement().addList("apiKey"));
  }
}
