package com.picopilot.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI piCopilotOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("PI Security Copilot API")
                .description("REST API for PI discovery, encryption migration, and audit")
                .version("1.0.0")
                .contact(new Contact()
                    .name("PI Copilot Team")
                    .email("picopilot@example.com"))
                .license(new License()
                    .name("Proprietary")
                    .url("https://example.com/license")))
            .addSecurityItem(new SecurityRequirement().addList("oidc"))
            .components(new Components()
                .addSecuritySchemes("oidc", new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .flows(new OAuthFlows()
                        .authorizationCode(new OAuthFlow()
                            .authorizationUrl("/oauth2/authorization")
                            .tokenUrl("/oauth2/token")))));
    }
}