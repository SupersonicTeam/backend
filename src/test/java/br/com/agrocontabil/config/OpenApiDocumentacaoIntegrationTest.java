package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.security.RotasEscritorio;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import br.com.agrocontabil.support.IdentidadeMockadaConfig;

/**
 * Documentação da API (issue #154): todo mapeamento tem {@code @Operation}, todo DTO tem
 * {@code @Schema} e o JSON de cada grupo do Swagger responde. Rota nova sem documentação
 * quebra o build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IdentidadeMockadaConfig.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Documentação OpenAPI: operações, DTOs e grupos (#154)")
class OpenApiDocumentacaoIntegrationTest {

  private static final String PACOTE = "br.com.agrocontabil";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mapeamentos;

  @Autowired private MockMvc mockMvc;

  /**
   * Rotas fora do Swagger por desenho: o "ask" do Caddy (#464) fica fora de {@code /api}, só na
   * rede interna. O teste {@code askForaDoSwagger} prova que nenhum grupo o publica.
   */
  private static final Set<String> FORA_DO_SWAGGER = Set.of(RotasEscritorio.TLS_ASK);

  private List<Map.Entry<RequestMappingInfo, HandlerMethod>> mapeamentosDoProjeto() {
    return mapeamentos.getHandlerMethods().entrySet().stream()
        .filter(e -> e.getValue().getBeanType().getName().startsWith(PACOTE))
        .toList();
  }

  @Test
  @DisplayName("todo mapeamento do projeto tem @Operation com resumo")
  void todoMapeamentoTemOperation() {
    List<String> semOperation = new ArrayList<>();
    for (var e : mapeamentosDoProjeto()) {
      Operation op = e.getValue().getMethodAnnotation(Operation.class);
      if (op == null || op.summary().isBlank()) {
        semOperation.add(e.getKey() + " -> " + e.getValue().getShortLogMessage());
      }
    }
    assertThat(mapeamentosDoProjeto()).isNotEmpty();
    assertThat(semOperation).as("mapeamentos sem @Operation(summary)").isEmpty();
  }

  @Test
  @DisplayName("todo caminho do projeto cai em pelo menos um grupo do Swagger")
  void todoCaminhoPertenceAUmGrupo() {
    AntPathMatcher matcher = new AntPathMatcher();
    List<String> orfaos = new ArrayList<>();
    for (var e : mapeamentosDoProjeto()) {
      for (String caminho : e.getKey().getPathPatternsCondition().getPatternValues()) {
        if (FORA_DO_SWAGGER.contains(caminho)) {
          continue;
        }
        boolean coberto = OpenApiConfig.GRUPOS.values().stream()
            .flatMap(java.util.Arrays::stream)
            .anyMatch(padrao -> matcher.match(padrao, caminho));
        if (!coberto) {
          orfaos.add(caminho);
        }
      }
    }
    assertThat(orfaos).as("caminhos fora de qualquer grupo").isEmpty();
  }

  @Test
  @DisplayName("o ask do Caddy nao aparece em nenhum grupo nem no JSON geral (#464)")
  void askForaDoSwagger() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['" + RotasEscritorio.TLS_ASK + "']").doesNotExist());
  }

  @Test
  @DisplayName("todo DTO (e DTO aninhado) tem @Schema no nível da classe")
  void todoDtoTemSchema() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(
              org.springframework.beans.factory.annotation.AnnotatedBeanDefinition def) {
            return true;
          }
        };
    scanner.addIncludeFilter(new RegexPatternTypeFilter(java.util.regex.Pattern.compile(".*")));
    List<String> semSchema = new ArrayList<>();
    for (var def : scanner.findCandidateComponents(PACOTE + ".dto")) {
      Class<?> tipo = Class.forName(def.getBeanClassName());
      if (tipo.isAnnotation() || tipo.isEnum() || tipo.getName().endsWith("Test") || tipo.isAnonymousClass() || tipo.getSimpleName().isEmpty()) {
        continue;
      }
      if (tipo.getAnnotation(Schema.class) == null) {
        semSchema.add(tipo.getName());
      }
    }
    assertThat(semSchema).as("DTOs sem @Schema").isEmpty();
  }

  @Test
  @DisplayName("o JSON de cada grupo e o geral respondem com as operações do grupo")
  void jsonDeCadaGrupoResponde() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("AgroConIA API"));
    for (String grupo : Set.copyOf(OpenApiConfig.GRUPOS.keySet())) {
      mockMvc.perform(get("/v3/api-docs/" + grupo)).andExpect(status().isOk())
          .andExpect(jsonPath("$.paths").isNotEmpty());
    }
  }

  @Test
  @DisplayName("a lista de grupos do Swagger UI traz os seis grupos")
  void swaggerConfigListaOsGrupos() throws Exception {
    mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
        .andExpect(jsonPath("$.urls.length()").value(OpenApiConfig.GRUPOS.size()));
  }
}
