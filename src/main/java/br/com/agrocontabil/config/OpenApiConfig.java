package br.com.agrocontabil.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI agroContabilOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AgroContabil API")
                        .description("""
                                API de auxilio ao produtor rural e ao escritorio de contabilidade \
                                na reforma tributaria do agronegocio (IBS/CBS — EC 132/2023 + LC \
                                214/2025).

                                **Modulo atual:** emissao de NF-e modelo 55 (CRUD, geracao e \
                                assinatura de XML, DANFE em PDF, importacao de XML, geracao de NFA, \
                                envio/consulta/cancelamento na SEFAZ).

                                **Autenticacao:** em stand-by ate a Sprint 8. O produtor \
                                "autenticado" e injetado por um filtro mock; todas as operacoes sao \
                                restritas a esse produtor (anti-IDOR: 404 para recurso de outro \
                                produtor, nunca 403).

                                **Contrato de erro:** toda resposta de erro segue o envelope \
                                `ApiError` com a lista `errors[]`, onde cada item traz \
                                `code`/`field`/`message`/`suggestion`/`rejectedValue`/`expectedValue`.\
                                """)
                        .version("v0.0.1")
                        .contact(new Contact()
                                .name("Supersonic Team")
                                .email("weissmurillo.dev@gmail.com"))
                        .license(new License().name("Uso academico - TCC 2026")))
                .externalDocs(new ExternalDocumentation()
                        .description("Repositorio e guia de arquitetura (CLAUDE.md)")
                        .url("https://github.com/SupersonicTeam/backend"))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Ambiente local de desenvolvimento")));
    }
}
