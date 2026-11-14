package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.MetodoVerificacaoCnpj;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.VerificacaoCnpjService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lado público do endereço do escritório pela cadeia real (issue #464): o
 * {@code GET /api/publico/escritorios/{slug}} da tela de login e o "ask" do Caddy em
 * {@code /interno/tls/permitido}. Ambos sem token; o mesmo 404 para tudo que não é servido,
 * inclusive o escritório com o CNPJ não verificado (#520).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Endereco do escritorio: endpoint publico e ask do Caddy (#464)")
class EnderecoPublicoIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  /** A chave do application-test.yml. */
  private static final String CHAVE = "chave-do-ask-so-dos-testes-0123456789abcdef";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private VerificacaoCnpjService verificacaoCnpj;
  @Autowired private EscritorioRepository escritorioRepository;

  private UUID escritorioA;
  private UUID escritorioB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    escritorioA = escritorioDe(cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Contabilidade Rural Oeste", "52998224725",
        "admin@rural-oeste.com.br", SENHA)));
    escritorioB = escritorioDe(cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Joao da Silva", "39053344705", "joao@contador.com.br", SENHA)));
    jdbcTemplate.update("UPDATE escritorio SET slug = 'ruraloeste', cnpj = '11222333000181',"
        + " cnpj_verificado_em = CURRENT_TIMESTAMP, cnpj_verificacao_metodo = 'MANUAL'"
        + " WHERE id = ?", escritorioA);
  }

  private UUID escritorioDe(CadastroResponse contador) {
    return jdbcTemplate.queryForObject("SELECT escritorio_id FROM contadores WHERE id = ?",
        UUID.class, contador.usuarioId());
  }

  private ResultActions publico(String slug) throws Exception {
    return mockMvc.perform(get("/api/publico/escritorios/{slug}", slug));
  }

  private ResultActions ask(String dominio, String chave) throws Exception {
    var requisicao = get("/interno/tls/permitido");
    if (dominio != null) {
      requisicao.param("domain", dominio);
    }
    if (chave != null) {
      requisicao.param("chave", chave);
    }
    return mockMvc.perform(requisicao);
  }

  private JsonNode semInstante(ResultActions resposta) throws Exception {
    JsonNode no = objectMapper.readTree(resposta.andReturn().getResponse().getContentAsString());
    ((ObjectNode) no).remove("timestamp");
    ((ObjectNode) no).remove("path");
    return no;
  }

  /** Carência de 90 dias do slug antigo, apontando para o escritório que o tinha. */
  private void emCarencia(String slug, UUID escritorio) {
    jdbcTemplate.update("INSERT INTO escritorio_slug_anterior (slug, escritorio_id, liberado_em)"
        + " VALUES (?, ?, DATEADD('DAY', 90, CURRENT_TIMESTAMP))", slug, escritorio);
  }

  @Test
  @DisplayName("endereco ativo: 200 so com slug e nome, cache publico de 5 minutos, sem token")
  void enderecoAtivo() throws Exception {
    publico("ruraloeste")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
        .andExpect(jsonPath("$.slug").value("ruraloeste"))
        .andExpect(jsonPath("$.nome").value("Contabilidade Rural Oeste"))
        .andExpect(jsonPath("$.slugAtual").doesNotExist())
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.cnpj").doesNotExist());
    // Um Bearer vencido ou lixo, anexado pelo interceptor, nao derruba a rota publica.
    mockMvc.perform(get("/api/publico/escritorios/ruraloeste")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token.vencido.qualquer"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("slug em carencia de escritorio com outro endereco: 200 com o slugAtual")
  void carenciaRedireciona() throws Exception {
    emCarencia("rural-oeste", escritorioA);

    publico("rural-oeste")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("rural-oeste"))
        .andExpect(jsonPath("$.slugAtual").value("ruraloeste"));
  }

  @Test
  @DisplayName("o mesmo 404 para inexistente, maiuscula, reservado, sem CNPJ, carencia vencida, apagado ou sem slug ativo")
  void naoServidoTemUmaRespostaSo() throws Exception {
    JsonNode inexistente = semInstante(publico("inexistente").andExpect(status().isNotFound())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
            org.hamcrest.Matchers.containsString("no-store"))));
    assertThat(inexistente.get("errors").get(0).get("code").asText())
        .isEqualTo("ENDERECO_NAO_ENCONTRADO");

    // Carencia vencida; carencia de escritorio apagado; carencia de escritorio que hoje nao tem
    // endereco (o B, que nunca teve slug): nenhuma serve.
    jdbcTemplate.update("INSERT INTO escritorio_slug_anterior (slug, escritorio_id, liberado_em)"
        + " VALUES ('vencido', ?, DATEADD('DAY', -1, CURRENT_TIMESTAMP))", escritorioA);
    emCarencia("deapagado", null);
    emCarencia("semdestino", escritorioB);

    for (String slug : new String[] {"RuralOeste", "www", "ceres", "xn--rural", "vencido",
        "deapagado", "semdestino"}) {
      assertThat(semInstante(publico(slug).andExpect(status().isNotFound()))).as(slug)
          .isEqualTo(inexistente);
    }
    // Sem CNPJ: o endereco existe, mas nao e servido.
    jdbcTemplate.update("UPDATE escritorio SET slug = 'semcnpj' WHERE id = ?", escritorioB);
    assertThat(semInstante(publico("semcnpj").andExpect(status().isNotFound())))
        .isEqualTo(inexistente);
    // Com CNPJ e sem a verificacao (#520): reservado, nunca servido, e a mesma resposta.
    jdbcTemplate.update("UPDATE escritorio SET cnpj = '11444777000161' WHERE id = ?", escritorioB);
    assertThat(semInstante(publico("semcnpj").andExpect(status().isNotFound())))
        .isEqualTo(inexistente);
  }

  @Test
  @DisplayName("CNPJ nao verificado: 404 no publico, no ask e na carencia ate a verificacao; trocar o CNPJ derruba de novo (#520)")
  void soVerificadoEServido() throws Exception {
    jdbcTemplate.update("UPDATE escritorio SET slug = 'campoverde', cnpj = '11444777000161'"
        + " WHERE id = ?", escritorioB);
    emCarencia("campo-verde", escritorioB);
    publico("campoverde").andExpect(status().isNotFound());
    publico("campo-verde").andExpect(status().isNotFound());
    ask("campoverde.agroconia.com.br", CHAVE).andExpect(status().isNotFound());
    ask("campo-verde.agroconia.com.br", CHAVE).andExpect(status().isNotFound());

    verificacaoCnpj.verificar(escritorioB, "11.444.777/0001-61", MetodoVerificacaoCnpj.MANUAL);
    publico("campoverde").andExpect(status().isOk())
        .andExpect(jsonPath("$.nome").value("Joao da Silva"));
    publico("campo-verde").andExpect(status().isOk())
        .andExpect(jsonPath("$.slugAtual").value("campoverde"));
    ask("campoverde.agroconia.com.br", CHAVE).andExpect(status().isOk());
    ask("campo-verde.agroconia.com.br", CHAVE).andExpect(status().isOk());

    // Trocar o nome mantem a verificacao; trocar o CNPJ a derruba na hora.
    var escritorio = escritorioRepository.findById(escritorioB).orElseThrow();
    escritorio.setNome("Campo Verde Contabilidade");
    escritorioRepository.saveAndFlush(escritorio);
    ask("campoverde.agroconia.com.br", CHAVE).andExpect(status().isOk());
    escritorio = escritorioRepository.findById(escritorioB).orElseThrow();
    escritorio.setCnpj("11222333000181");
    escritorioRepository.saveAndFlush(escritorio);
    ask("campoverde.agroconia.com.br", CHAVE).andExpect(status().isNotFound());
    publico("campoverde").andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("ask: 200 sem corpo so com a chave certa e um endereco servido, sem token")
  void askPermite() throws Exception {
    ask("ruraloeste.agroconia.com.br", CHAVE).andExpect(status().isOk())
        .andExpect(content().string(""));
    emCarencia("rural-oeste", escritorioA);
    ask("rural-oeste.agroconia.com.br", CHAVE).andExpect(status().isOk());
  }

  @Test
  @DisplayName("ask: 404 sem chave, com a chave errada e para dominio que nao e de escritorio servido")
  void askRecusa() throws Exception {
    ask("ruraloeste.agroconia.com.br", null).andExpect(status().isNotFound());
    ask("ruraloeste.agroconia.com.br", CHAVE + "x").andExpect(status().isNotFound());
    ask(null, CHAVE).andExpect(status().isNotFound());
    for (String dominio : new String[] {"x.ruraloeste.agroconia.com.br",
        "agroconia.com.br.evil.com", "RURALOESTE.agroconia.com.br", "xn--rural.agroconia.com.br",
        "www.agroconia.com.br", "api.agroconia.com.br", "agroconia.com.br",
        "inexistente.agroconia.com.br"}) {
      ask(dominio, CHAVE).andExpect(status().isNotFound());
    }
  }

  @Test
  @DisplayName("ask: so GET; outro metodo cai na regra geral (401)")
  void askSoGet() throws Exception {
    mockMvc.perform(post("/interno/tls/permitido").param("domain", "ruraloeste.agroconia.com.br")
            .param("chave", CHAVE).contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(status().isUnauthorized());
  }
}
