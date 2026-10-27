package br.com.agrocontabil.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadados da documentacao OpenAPI / Swagger UI.
 *
 * <p>Swagger UI disponivel em {@code /swagger-ui.html} e o JSON em
 * {@code /v3/api-docs} (liberados no SecurityConfig).
 *
 * <p>O server base NAO e fixado aqui de proposito: o springdoc o calcula a partir da
 * requisicao (respeitando host, porta, esquema e reverse proxy). Fixar {@code localhost}
 * quebraria o Swagger UI em deploy atras de proxy/HTTPS.
 */
@Configuration
public class OpenApiConfig {

    /** Nome do esquema de seguranca Bearer na especificacao (issue #206). */
    static final String ESQUEMA_BEARER = "bearerAuth";

    @Bean
    public OpenAPI agroContabilOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(ESQUEMA_BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_BEARER))
                .info(new Info()
                        .title("AgroContabil API")
                        .description("""
                                API de auxílio ao produtor rural e ao escritório de contabilidade \
                                na reforma tributária do agronegócio (IBS/CBS — EC 132/2023 + LC \
                                214/2025).

                                **Módulo atual:** emissão de NF-e modelo 55 (CRUD, geração e \
                                assinatura de XML, DANFE em PDF, importação de XML, geração de NFA, \
                                envio/consulta/cancelamento na SEFAZ).

                                **Autenticação:** JWT Bearer. Entre em `POST /api/auth/login` \
                                (e-mail e senha) e envie o `accessToken` no header \
                                `Authorization: Bearer`. Em dev o header `X-Usuario-Id` \
                                segue aceito sem token. Anti-IDOR: 404 para recurso de outro \
                                produtor, nunca 403.

                                **Contrato de erro:** toda resposta de erro segue o envelope \
                                `ApiError` com a lista `errors[]`, onde cada item traz \
                                `code`/`field`/`message`/`suggestion`/`rejectedValue`/`expectedValue`.\
                                """)
                        .version("v0.0.1")
                        .contact(new Contact()
                                .name("Supersonic Team")
                                .email("weissmurillo.dev@gmail.com"))
                        .license(new License().name("Uso acadêmico - TCC 2026")))
                .externalDocs(new ExternalDocumentation()
                        .description("Repositório e guia de arquitetura (CLAUDE.md)")
                        .url("https://github.com/SupersonicTeam/backend"));
    }
}
