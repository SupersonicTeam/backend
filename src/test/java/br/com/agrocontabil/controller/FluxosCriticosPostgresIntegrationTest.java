package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.PostgresIntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Fluxos críticos ponta a ponta contra PostgreSQL 16 real (issue #152): criar a nota, gerar o XML,
 * transmitir ao mock da SEFAZ até {@code AUTORIZADA}, cancelar dentro e fora do prazo (RN006) e
 * reabrir a apuração dentro e fora do limite (RN005). Tudo pelo HTTP, com JWT de login real e o
 * schema criado pelo Liquibase — o que o H2 dos outros testes não prova. A importação de XML
 * segue coberta em H2 ({@code ImportacaoNotaFiscalIntegrationTest}).
 */
@DisplayName("Fluxos criticos em PostgreSQL: emissao, cancelamento e reabertura (#152)")
class FluxosCriticosPostgresIntegrationTest extends PostgresIntegrationTestBase {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String CPF_PRODUTOR = "52998224725";
  private static final String MOTIVO =
      "{\"motivo\": \"Cancelamento por erro de digitacao nos dados do destinatario\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CadastroService cadastroService;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private Clock clock;

  private CadastroResponse produtor;
  private CadastroResponse contador;
  private String tokenProdutor;
  private String tokenContador;

  @BeforeEach
  void setUp() throws Exception {
    limparDominio();
    produtor = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor Fluxo", CPF_PRODUTOR, "p@fazenda.com.br", SENHA));
    contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio Fluxo", "11222333000181", "c@escritorio.com.br",
        SENHA));
    // Emitente completo (IE e endereço): pré-condição da emissão desde a #88.
    Produtor emitente = produtorRepository.findById(produtor.usuarioId()).orElseThrow();
    emitente.setEstado(ProdutorTestFixtures.UF);
    emitente.setInscricaoEstadual(ProdutorTestFixtures.INSCRICAO_ESTADUAL);
    emitente.setEndereco(ProdutorTestFixtures.enderecoCompleto());
    produtorRepository.save(emitente);

    tokenProdutor = token("p@fazenda.com.br");
    tokenContador = token("c@escritorio.com.br");
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA)))
        .andExpect(status().isOk()).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private String corpoNota(int numero, OffsetDateTime emissao) {
    return """
        {
          "numero": %d,
          "serie": 1,
          "naturezaOperacao": "Venda de producao rural",
          "dataEmissao": "%s",
          "destinatarioNome": "Jose Comprador",
          "destinatarioCpfCnpj": "39053344705",
          "destinatarioEndereco": {
            "logradouro": "Rua das Acacias", "numero": "250", "municipio": "Uberaba",
            "codigoMunicipioIbge": "3170206", "uf": "MG", "cep": "38100000"
          },
          "itens": [
            {"numeroItem": 1, "descricao": "Soja em graos", "ncm": "12019000", "cfop": "6101",
             "cstIcms": "41", "unidade": "KG", "quantidade": 500.0, "valorUnitario": 2.00}
          ]
        }
        """.formatted(numero, emissao);
  }

  /** Cria a nota (201 RASCUNHO) e devolve o id. */
  private UUID criar(int numero, OffsetDateTime emissao) throws Exception {
    MvcResult criada = mockMvc.perform(post("/api/notas")
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor)
            .contentType(MediaType.APPLICATION_JSON).content(corpoNota(numero, emissao)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("RASCUNHO"))
        .andReturn();
    return UUID.fromString(objectMapper.readTree(criada.getResponse().getContentAsString())
        .get("id").asText());
  }

  /** Cria, gera o XML e transmite ao mock: devolve o id de uma nota AUTORIZADA. */
  private UUID autorizada(int numero, OffsetDateTime emissao) throws Exception {
    UUID id = criar(numero, emissao);
    mockMvc.perform(post("/api/notas/{id}/gerar-xml", id)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("XML_GERADO"));
    mockMvc.perform(post("/api/notas/{id}/enviar-sefaz", id)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AUTORIZADA"));
    return id;
  }

  private String statusDaNota(UUID id) {
    return jdbc.queryForObject("SELECT status FROM nota_fiscal WHERE id = ?", String.class, id);
  }

  @Test
  @DisplayName("criar, gerar XML e enviar ao mock: a nota termina AUTORIZADA com chave e protocolo")
  void emissaoAteAutorizada() throws Exception {
    UUID id = autorizada(101, OffsetDateTime.now(clock));

    assertThat(statusDaNota(id)).isEqualTo("AUTORIZADA");
    var linha = jdbc.queryForMap("SELECT chave_acesso, protocolo_autorizacao"
        + " FROM nota_fiscal WHERE id = ?", id);
    assertThat((String) linha.get("chave_acesso")).hasSize(44);
    assertThat((String) linha.get("protocolo_autorizacao")).isNotBlank();
    mockMvc.perform(get("/api/notas/{id}/xml", id)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML));
  }

  @Test
  @DisplayName("cancelar dentro dos 30 dias (RN006): 200 e a nota fica CANCELADA")
  void cancelaDentroDoPrazo() throws Exception {
    UUID id = autorizada(102, OffsetDateTime.now(clock));

    mockMvc.perform(post("/api/notas/{id}/cancelar", id)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor)
            .contentType(MediaType.APPLICATION_JSON).content(MOTIVO))
        .andExpect(status().isOk());

    assertThat(statusDaNota(id)).isEqualTo("CANCELADA");
  }

  @Test
  @DisplayName("cancelar passados os 30 dias (RN006): 422 e a nota segue AUTORIZADA")
  void naoCancelaForaDoPrazo() throws Exception {
    UUID id = autorizada(103, OffsetDateTime.now(clock).minusDays(40));

    mockMvc.perform(post("/api/notas/{id}/cancelar", id)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor)
            .contentType(MediaType.APPLICATION_JSON).content(MOTIVO))
        .andExpect(status().isUnprocessableEntity());

    assertThat(statusDaNota(id)).isEqualTo("AUTORIZADA");
  }

  private void apuracaoFechada(YearMonth periodo) {
    jdbc.update("INSERT INTO apuracao_mensal (id, produtor_id, periodo, status, debito_ibs,"
        + " debito_cbs, credito_ibs, credito_cbs, saldo_ibs, saldo_cbs, total_notas,"
        + " data_fechamento) VALUES (?, ?, ?, 'FECHADO', 10, 20, 1, 2, 9, 18, 3, now())",
        UUID.randomUUID(), produtor.usuarioId(), periodo.toString());
  }

  private String statusDaApuracao(YearMonth periodo) {
    return jdbc.queryForObject("SELECT status FROM apuracao_mensal WHERE produtor_id = ?"
        + " AND periodo = ?", String.class, produtor.usuarioId(), periodo.toString());
  }

  @Test
  @DisplayName("reabrir o mes passado (RN005): 200 ABERTO; 4 meses atras: 422 e segue FECHADO")
  void reabreDentroEForaDoLimite() throws Exception {
    // Só neste teste o produtor está na carteira ativa do escritório. Nos de emissão ele não tem
    // contador: com contador vinculado, gerar o XML exige a nota APROVADA (#178).
    jdbc.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao, inicio)"
        + " SELECT gen_random_uuid(), escritorio_id, ?, 'ATIVO', now() FROM contadores"
        + " WHERE id = ?", produtor.usuarioId(), contador.usuarioId());
    YearMonth atual = YearMonth.now(clock);
    YearMonth dentro = atual.minusMonths(1);
    YearMonth fora = atual.minusMonths(4);
    apuracaoFechada(dentro);
    apuracaoFechada(fora);
    String rota = "/api/contador/produtores/{p}/apuracao/{periodo}/reabrir";

    mockMvc.perform(post(rota, produtor.usuarioId(), dentro.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABERTO"))
        .andExpect(jsonPath("$.periodo").value(dentro.toString()));
    assertThat(statusDaApuracao(dentro)).isEqualTo("ABERTO");

    mockMvc.perform(post(rota, produtor.usuarioId(), fora.toString())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("REABERTURA_FORA_PRAZO"));
    assertThat(statusDaApuracao(fora)).isEqualTo("FECHADO");
  }
}
