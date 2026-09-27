package com.saadat.config;

import com.saadat.config.props.AppProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc: /api/v3/api-docs and /api/swagger-ui.html, with a global bearer-JWT scheme. */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearer";

    @Bean
    public OpenAPI openApi(AppProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title("Saadat API")
                        .version("v1")
                        .description("Backend API for the dream-interpretation platform. Contract: frontend/src/api/types.ts"))
                .servers(List.of(new Server().url(properties.getApiUrl())))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
