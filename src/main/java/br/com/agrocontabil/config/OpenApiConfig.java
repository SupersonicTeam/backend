package br.com.agrocontabil.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadados da documentacao OpenAPI / Swagger UI.
 *
 * <p>Swagger UI disponivel em {@code /swagger-ui.html} e o JSON em
 * {@code /v3/api-docs} (liberados no SecurityConfig).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI agroContabilOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AgroContabil API")
                        .description("API de auxilio ao produtor rural e escritorio contabil "
                                + "na reforma tributaria do agronegocio (IBS/CBS).")
                        .version("v0.0.1")
                        .contact(new Contact()
                                .name("Supersonic Team")
                                .email("weissmurillo.dev@gmail.com"))
                        .license(new License().name("Uso academico - TCC 2026")));
    }
}
