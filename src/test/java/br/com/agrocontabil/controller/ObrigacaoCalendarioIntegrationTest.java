package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Catálogo de obrigações do escritório e o calendário fiscal derivado dele (issue #181), pela
 * cadeia real com JWT. Dois escritórios (A e B): o que A personaliza não muda nada em B, e o
 * catálogo de um não é alcançável pelo outro (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Obrigacoes do escritorio e calendario fiscal (#181)")
class ObrigacaoCalendarioIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String DAS = "00000000-0000-4000-8181-000000000001";
  private static final String DEFIS = "00000000-0000-4000-8181-000000000004";
  private static final String JANELA = "de=2026-03-01&ate=2026-03-31";

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse contadorA;
  private CadastroResponse contadorB;
  private CadastroResponse produtor;
  private CadastroResponse semEscritorio;
  private String tokenA;
  private String tokenB;
  private String tokenProdutor;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    contadorA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio A", "11222333000181", "a@escritorio.com.br", SENHA));
    contadorB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio B", "11444777000161", "b@escritorio.com.br", SENHA));
    // PJ do Simples: DAS, DEFIS e Funrural valem; DCTFWeb (Presumido/Real) e DAS-MEI nao.
    produtor = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Agro Ltda", "11444777000242", "agro@ltda.com.br", SENHA));
    semEscritorio = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim", "52998224725", "joaquim@fazenda.com.br", SENHA));
    jdbcTemplate.update("UPDATE produtores SET regime_tributario = 'SIMPLES',"
        + " tipo_produtor = 'PESSOA_JURIDICA' WHERE id = ?", produtor.usuarioId());
    vincular(contadorA);
    tokenA = token("a@escritorio.com.br");
    tokenB = token("b@escritorio.com.br");
    tokenProdutor = token("agro@ltda.com.br");
  }

  private void vincular(CadastroResponse contador) {
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) SELECT RANDOM_UUID(), escritorio_id, ?, 'ATIVO', CURRENT_TIMESTAMP"
        + " FROM contadores WHERE id = ?", produtor.usuarioId(), contador.usuarioId());
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private ResultActions calendario(String token, UUID produtorId) throws Exception {
    return mockMvc.perform(get("/api/calendario-fiscal/{id}/vencimentos?" + JANELA, produtorId)
        .header(HttpHeaders.AUTHORIZATION, token));
  }

  private List<String> titulosDeObrigacao(String token, UUID produtorId) throws Exception {
    JsonNode itens = objectMapper.readTree(calendario(token, produtorId)
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    List<String> titulos = new ArrayList<>();
    itens.forEach(i -> {
      if ("obrigacao".equals(i.get("tipo").asText())) {
        titulos.add(i.get("titulo").asText() + "@" + i.get("dataVencimento").asText());
      }
    });
    return titulos;
  }

  private static String json(String titulo, String periodicidade, int dia, Integer mes) {
    return ("{\"titulo\":\"%s\",\"periodicidade\":\"%s\",\"dia\":%d,\"mes\":%s,"
        + "\"regimes\":[\"SIMPLES\"],\"origem\":\"Contrato do escritorio\"}")
        .formatted(titulo, periodicidade, dia, mes == null ? "null" : mes.toString());
  }

  @Test
  @DisplayName("catalogo: escritorio novo ve os 11 modelos; produtor nao entra (403)")
  void catalogoComModelos() throws Exception {
    mockMvc.perform(get("/api/contador/obrigacoes").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "11"))
        .andExpect(jsonPath("$[*].origemCatalogo", not(hasItem("PROPRIA"))))
        .andExpect(jsonPath("$[?(@.titulo == 'DCTFWeb')].regimes[*]",
            org.hamcrest.Matchers.containsInAnyOrder("PRESUMIDO", "REAL")));
    mockMvc.perform(get("/api/contador/obrigacoes").header(HttpHeaders.AUTHORIZATION,
            tokenProdutor))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("calendario: ocorrencias do catalogo que valem para o produtor + fechamento")
  void calendarioDoProdutor() throws Exception {
    List<String> titulos = titulosDeObrigacao(tokenProdutor, produtor.usuarioId());

    assertThat(titulos).contains("DAS / PGDAS-D@2026-03-20", "Funrural (recolhimento)@2026-03-20",
        "DEFIS@2026-03-31", "Fechamento da apuracao do mes@2026-03-31");
    assertThat(titulos).noneMatch(t -> t.startsWith("DCTFWeb") || t.startsWith("DAS-MEI")
        || t.startsWith("DIRPF"));
    calendario(tokenProdutor, produtor.usuarioId())
        .andExpect(jsonPath("$[?(@.titulo == 'DEFIS')].id",
            org.hamcrest.Matchers.contains(DEFIS + ":2026-03-31")))
        .andExpect(jsonPath("$[?(@.titulo == 'DEFIS')].origem",
            org.hamcrest.Matchers.contains("Res. CGSN 140/2018")))
        .andExpect(jsonPath("$[?(@.titulo == 'DEFIS')].periodicidade",
            org.hamcrest.Matchers.contains("ANUAL")));

    // Sem escritorio: so o fechamento da apuracao (RN004), que vale para todo produtor.
    assertThat(titulosDeObrigacao(token("joaquim@fazenda.com.br"), semEscritorio.usuarioId()))
        .containsExactly("Fechamento da apuracao do mes@2026-03-31");
  }

  @Test
  @DisplayName("editar modelo cria a versao do escritorio; o outro escritorio segue no modelo")
  void personalizarModelo() throws Exception {
    vincular(contadorB);
    mockMvc.perform(put("/api/contador/obrigacoes/{id}", DAS)
            .header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(json("DAS do escritorio A", "MENSAL", 18, null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.origemCatalogo").value("PERSONALIZADA"))
        .andExpect(jsonPath("$.modeloId").value(DAS));

    mockMvc.perform(get("/api/contador/obrigacoes").header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(jsonPath("$[?(@.id == '" + DAS + "')].dia",
            org.hamcrest.Matchers.contains(20)));
    // O contador ve so o catalogo do proprio escritorio; o produtor, o dos dois.
    assertThat(titulosDeObrigacao(tokenA, produtor.usuarioId()))
        .contains("DAS do escritorio A@2026-03-18").doesNotContain("DAS / PGDAS-D@2026-03-20");
    assertThat(titulosDeObrigacao(tokenB, produtor.usuarioId()))
        .contains("DAS / PGDAS-D@2026-03-20").doesNotContain("DAS do escritorio A@2026-03-18");
    assertThat(titulosDeObrigacao(tokenProdutor, produtor.usuarioId()))
        .contains("DAS do escritorio A@2026-03-18", "DAS / PGDAS-D@2026-03-20");
    // O modelo em si nao mudou.
    assertThat(jdbcTemplate.queryForObject("SELECT dia FROM obrigacao_fiscal WHERE id = ?",
        Integer.class, UUID.fromString(DAS))).isEqualTo(20);
  }

  @Test
  @DisplayName("desativar modelo some do calendario; reativar volta; restaurar padrao volta ao modelo")
  void desativarReativarRestaurar() throws Exception {
    String versao = objectMapper.readTree(mockMvc.perform(delete("/api/contador/obrigacoes/{id}",
            DEFIS).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ativa").value(false))
        .andExpect(jsonPath("$.origemCatalogo").value("PERSONALIZADA"))
        .andReturn().getResponse().getContentAsString()).get("id").asText();
    assertThat(titulosDeObrigacao(tokenProdutor, produtor.usuarioId()))
        .noneMatch(t -> t.startsWith("DEFIS"));

    mockMvc.perform(post("/api/contador/obrigacoes/{id}/reativar", versao)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(true));
    assertThat(titulosDeObrigacao(tokenProdutor, produtor.usuarioId())).contains("DEFIS@2026-03-31");

    mockMvc.perform(delete("/api/contador/obrigacoes/{id}", versao)
        .header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk());
    mockMvc.perform(post("/api/contador/obrigacoes/{id}/restaurar-padrao", versao)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(DEFIS))
        .andExpect(jsonPath("$.origemCatalogo").value("MODELO"))
        .andExpect(jsonPath("$.ativa").value(true));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM obrigacao_fiscal"
        + " WHERE modelo_id = ?", Integer.class, UUID.fromString(DEFIS))).isZero();
  }

  @Test
  @DisplayName("reativar modelo nunca personalizado nao cria versao (o modelo segue chegando)")
  void reativarModeloIntacto() throws Exception {
    mockMvc.perform(post("/api/contador/obrigacoes/{id}/reativar", DAS)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.origemCatalogo").value("MODELO"))
        .andExpect(jsonPath("$.ativa").value(true));

    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM obrigacao_fiscal"
        + " WHERE modelo = FALSE", Integer.class)).isZero();
  }

  @Test
  @DisplayName("obrigacao propria: so administrador cria; regras de mes; de outro escritorio 404")
  void obrigacaoPropria() throws Exception {
    mockMvc.perform(post("/api/contador/obrigacoes").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content(json("Inventario", "ANUAL", 15, null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("OBRIGACAO_INVALIDA"))
        .andExpect(jsonPath("$.errors[0].field").value("mes"));
    mockMvc.perform(post("/api/contador/obrigacoes").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content(json("Fechamento", "MENSAL", 10, 3)))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/contador/obrigacoes").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content(json("Inventario", "MENSAL", 40, null)))
        .andExpect(status().isBadRequest());

    String id = objectMapper.readTree(mockMvc.perform(post("/api/contador/obrigacoes")
            .header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(json("Inventario de estoque", "ANUAL", 15, 3)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.origemCatalogo").value("PROPRIA"))
        .andReturn().getResponse().getContentAsString()).get("id").asText();
    assertThat(titulosDeObrigacao(tokenProdutor, produtor.usuarioId()))
        .contains("Inventario de estoque@2026-03-15");

    mockMvc.perform(put("/api/contador/obrigacoes/{id}", id).header(HttpHeaders.AUTHORIZATION,
            tokenB).contentType(MediaType.APPLICATION_JSON)
            .content(json("Tomada", "ANUAL", 15, 3)))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/contador/obrigacoes/{id}", id)
            .header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/contador/obrigacoes/{id}/restaurar-padrao", id)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("OBRIGACAO_INVALIDA"));
  }

  @Test
  @DisplayName("membro que nao e administrador le o catalogo mas nao altera (403)")
  void soAdministradorAltera() throws Exception {
    jdbcTemplate.update("UPDATE contadores SET funcao_escritorio = 'ASSISTENTE' WHERE id = ?",
        contadorA.usuarioId());

    mockMvc.perform(get("/api/contador/obrigacoes").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk());
    mockMvc.perform(put("/api/contador/obrigacoes/{id}", DAS).header(HttpHeaders.AUTHORIZATION,
            tokenA).contentType(MediaType.APPLICATION_JSON)
            .content(json("DAS", "MENSAL", 18, null)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    mockMvc.perform(delete("/api/contador/obrigacoes/{id}", DAS)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden());
  }
}
