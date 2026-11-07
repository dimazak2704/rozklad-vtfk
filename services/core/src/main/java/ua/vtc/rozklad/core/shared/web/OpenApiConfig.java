package ua.vtc.rozklad.core.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description at {@code /v3/api-docs}; Swagger UI at {@code /swagger-ui.html}. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER = "bearer";

    @Bean
    OpenAPI coreOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Розклад ВТФК — core API").version("v1")
                        .description("Каталог, календар, навантаження, розклад, заміни, розпорядження, облік годин"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
