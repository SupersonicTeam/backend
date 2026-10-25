package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.RegistroAuditoria;
import br.com.agrocontabil.model.SituacaoConvite;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.repository.ConviteCarteiraRepository;
import br.com.agrocontabil.repository.RegistroAuditoriaRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.EnvioConviteCarteira;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Carteira do contador pela cadeia real, com JWT (issue #177): convite com aceite do produtor,
 * lista com atenção, ficha, etiquetas, ciclo do vínculo e leitura das notas do cliente.
 *
 * <p>Dois produtores em todo cenário de acesso: A (cliente) e B (fora da carteira) — o escritório
 * nunca alcança B, e A nunca age sobre o convite ou o vínculo de B (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabaseCleaner.class, CarteiraIntegrationTest.CapturaConvite.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "app.carteira.convite.max-por-dia=5")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Carteira do contador: convite, lista, ficha e notas do cliente (#177)")
class CarteiraIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL_A = "joaquim@fazenda.com.br";
  private static final String EMAIL_B = "beatriz@sitio.com.br";
  private static final String EMAIL_CONTADOR = "escritorio@campo.com.br";
  private static final String EMAIL_OUTRO_CONTADOR = "outro@escritorio.com.br";
  private static final String CPF_A = "52998224725";

  /** E-mails de convite enviados, na ordem. */
  static final BlockingQueue<String> ENVIADOS = new LinkedBlockingQueue<>();

  /** Troca o envio real por uma captura. */
  @TestConfiguration
  static class CapturaConvite {
    @Bean
    @Primary
    EnvioConviteCarteira envioConviteCapturado() {
      return (destinatario, escritorio, link, validade) ->
          ENVIADOS.add(destinatario + "|" + escritorio);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private ConviteCarteiraRepository conviteRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private RegistroAuditoriaRepository auditoriaRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse produtorA;
  private CadastroResponse produtorB;
  private CadastroResponse contador;
  private String tokenA;
  private String tokenB;
  private String tokenContador;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    ENVIADOS.clear();
    produtorA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim Conceição", CPF_A, EMAIL_A, SENHA));
    produtorB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Beatriz Souza", "11144477735", EMAIL_B, SENHA));
    contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio Campo", "11222333000181", EMAIL_CONTADOR, SENHA));
    jdbcTemplate.update("UPDATE produtores SET estado = 'MT', regime_tributario = 'SIMPLES',"
        + " tipo_produtor = 'PESSOA_FISICA' WHERE id = ?", produtorA.usuarioId());
    jdbcTemplate.update("UPDATE produtores SET estado = 'PR' WHERE id = ?",
        produtorB.usuarioId());
    tokenA = token(EMAIL_A);
    tokenB = token(EMAIL_B);
    tokenContador = token(EMAIL_CONTADOR);
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private UUID escritorioDe(UUID contadorId) {
    return jdbcTemplate.queryForObject("SELECT escritorio_id FROM contadores WHERE id = ?",
        UUID.class, contadorId);
  }

  private ResultActions convidar(String token, String email) throws Exception {
    return mockMvc.perform(post("/api/contador/convites").header(HttpHeaders.AUTHORIZATION, token)
        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"%s\"}".formatted(email)));
  }

  private JsonNode json(ResultActions resultado) throws Exception {
    return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
  }

  /** Convite + aceite do produtor A: A entra na carteira. */
  private void clienteA() throws Exception {
    convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted());
    String conviteId = json(mockMvc.perform(get("/api/convites")
        .header(HttpHeaders.AUTHORIZATION, tokenA))).get(0).get("id").asText();
    mockMvc.perform(post("/api/convites/{id}/aceitar", conviteId)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk());
  }

  private ResultActions comoContador(String url, Object... variaveis) throws Exception {
    return mockMvc.perform(get(url, variaveis).header(HttpHeaders.AUTHORIZATION, tokenContador));
  }

  private void inserirNota(UUID produtorId, long numero, String status) {
    jdbcTemplate.update("INSERT INTO nota_fiscal (id, produtor_id, numero, serie, modelo,"
        + " natureza_operacao, data_emissao, destinatario_nome, destinatario_cpf_cnpj,"
        + " valor_total_produtos, valor_total_nota, status, origem, tipo_documento, version)"
        + " VALUES (RANDOM_UUID(), ?, ?, 1, '55', 'Venda', CURRENT_TIMESTAMP, 'Comprador',"
        + " '12345678901', 100, 100, ?, 'PROPRIA', 'NFE', 0)", produtorId, numero, status);
  }

  private void inserirGuia(UUID produtorId, LocalDate vencimento, String status) {
    jdbcTemplate.update("INSERT INTO guias_fiscais (id, produtor_id, tipo_guia, data_vencimento,"
        + " valor, status) VALUES (RANDOM_UUID(), ?, 'ICMS', ?, 100, ?)",
        produtorId, vencimento, status);
  }

  @Test
  @DisplayName("convite: sempre 202, com ou sem conta; o e-mail sai depois do commit")
  void conviteSempre202() throws Exception {
    convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted())
        .andExpect(jsonPath("$").doesNotExist());
    convidar(tokenContador, "ninguem@lugar.nenhum").andExpect(status().isAccepted())
        .andExpect(jsonPath("$").doesNotExist());

    assertThat(ENVIADOS.poll(5, TimeUnit.SECONDS)).isEqualTo(EMAIL_A + "|Escritorio Campo");
    assertThat(ENVIADOS.poll(5, TimeUnit.SECONDS))
        .isEqualTo("ninguem@lugar.nenhum|Escritorio Campo");
    // A lista do escritorio nao distingue quem tem conta.
    comoContador("/api/contador/convites").andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$[*].situacao").value(org.hamcrest.Matchers.everyItem(
            org.hamcrest.Matchers.is("PENDENTE"))));
  }

  @Test
  @DisplayName("convite: produtor nao convida (403); reenviar substitui o pendente")
  void conviteReenviadoSubstitui() throws Exception {
    convidar(tokenA, EMAIL_B).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));

    convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted());
    convidar(tokenContador, "JOAQUIM@Fazenda.com.br").andExpect(status().isAccepted());

    mockMvc.perform(get("/api/convites").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].escritorio").value("Escritorio Campo"));
  }

  @Test
  @DisplayName("convite: reenviar logo depois nao manda outro e-mail (sem spam a uma pessoa)")
  void reenvioRapidoNaoDisparaEmail() throws Exception {
    for (int i = 0; i < 10; i++) {
      convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted());
    }

    assertThat(ENVIADOS.poll(5, TimeUnit.SECONDS)).isNotNull();
    assertThat(ENVIADOS.poll(1, TimeUnit.SECONDS)).isNull();
    mockMvc.perform(get("/api/convites").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(header().string("X-Total-Count", "1"));
  }

  @Test
  @DisplayName("convite: reenvio horas depois tambem nao manda e-mail (um por endereco por dia)")
  void reenvioNoMesmoDiaNaoEscapaDoTeto() throws Exception {
    convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted());
    assertThat(ENVIADOS.poll(5, TimeUnit.SECONDS)).isNotNull();
    jdbcTemplate.update("UPDATE convite_carteira SET criado_em = DATEADD('HOUR', -5,"
        + " CURRENT_TIMESTAMP)");

    convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted());

    assertThat(ENVIADOS.poll(1, TimeUnit.SECONDS)).isNull();
  }

  @Test
  @DisplayName("convite: teto diario por escritorio vira 429 com Retry-After")
  void conviteComTeto() throws Exception {
    for (int i = 0; i < 5; i++) {
      convidar(tokenContador, "convidado" + i + "@x.com").andExpect(status().isAccepted());
    }
    convidar(tokenContador, "sexto@x.com").andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.errors[0].code").value("LIMITE_CONVITES"));
  }

  @Test
  @DisplayName("convite: Retry-After conta ate o convite mais antigo sair da janela de 24 h")
  void retryAfterPeloConviteMaisAntigo() throws Exception {
    for (int i = 0; i < 5; i++) {
      convidar(tokenContador, "convidado" + i + "@x.com").andExpect(status().isAccepted());
    }
    jdbcTemplate.update("UPDATE convite_carteira SET criado_em = DATEADD('HOUR', -22,"
        + " CURRENT_TIMESTAMP)");

    String espera = convidar(tokenContador, "sexto@x.com")
        .andExpect(status().isTooManyRequests())
        .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

    // Faltam ~2 h para o mais antigo sair da janela.
    assertThat(Long.parseLong(espera)).isBetween(7000L, 7300L);
  }

  @Test
  @DisplayName("aceite: so o dono do e-mail; B nao ve nem aceita o convite de A (404)")
  void aceiteSoDoDono() throws Exception {
    convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted());
    String conviteId = json(mockMvc.perform(get("/api/convites")
        .header(HttpHeaders.AUTHORIZATION, tokenA))).get(0).get("id").asText();

    mockMvc.perform(get("/api/convites").header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(header().string("X-Total-Count", "0"));
    mockMvc.perform(post("/api/convites/{id}/aceitar", conviteId)
            .header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/convites/{id}/aceitar", conviteId)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isForbidden());
    // Sem aceite, nada de vinculo: o escritorio nao alcanca A.
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(status().isNotFound());

    mockMvc.perform(post("/api/convites/{id}/aceitar", conviteId)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("ACEITO"));
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacaoVinculo").value("ATIVO"));
    // Respondido, o convite some e nao e aceito de novo.
    mockMvc.perform(post("/api/convites/{id}/aceitar", conviteId)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("recusa: convite respondido, sem vinculo")
  void recusa() throws Exception {
    convidar(tokenContador, EMAIL_A).andExpect(status().isAccepted());
    String conviteId = json(mockMvc.perform(get("/api/convites")
        .header(HttpHeaders.AUTHORIZATION, tokenA))).get(0).get("id").asText();

    mockMvc.perform(post("/api/convites/{id}/recusar", conviteId)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("RECUSADO"));

    assertThat(conviteRepository.findById(UUID.fromString(conviteId)).orElseThrow()
        .getSituacao()).isEqualTo(SituacaoConvite.RECUSADO);
    assertThat(vinculoRepository.findIdsDoEscritorio(escritorioDe(contador.usuarioId()))).isEmpty();
  }

  @Test
  @DisplayName("convite feito antes do cadastro aparece para a conta criada com o e-mail")
  void conviteAntesDoCadastro() throws Exception {
    convidar(tokenContador, "novo@produtor.com.br").andExpect(status().isAccepted());
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Novo Produtor", "39053344705", "novo@produtor.com.br", SENHA));

    mockMvc.perform(get("/api/convites").header(HttpHeaders.AUTHORIZATION,
            token("novo@produtor.com.br")))
        .andExpect(header().string("X-Total-Count", "1"));
  }

  @Test
  @DisplayName("lista: so contador, so a carteira ativa, com atencao agregada e X-Total-Count")
  void listaComAtencao() throws Exception {
    clienteA();
    LocalDate hoje = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    inserirNota(produtorA.usuarioId(), 1, "COM_INCONSISTENCIAS");
    inserirNota(produtorA.usuarioId(), 2, "RASCUNHO");
    inserirNota(produtorB.usuarioId(), 3, "COM_INCONSISTENCIAS");
    inserirGuia(produtorA.usuarioId(), hoje.minusDays(3), "VENCIDA");
    inserirGuia(produtorA.usuarioId(), hoje.minusDays(1), "PENDENTE");
    inserirGuia(produtorA.usuarioId(), hoje.plusDays(3), "PENDENTE");
    inserirGuia(produtorA.usuarioId(), hoje.plusDays(30), "PENDENTE");

    mockMvc.perform(get("/api/contador/produtores").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden());
    comoContador("/api/contador/produtores")
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].id").value(produtorA.usuarioId().toString()))
        .andExpect(jsonPath("$[0].cpfCnpj").value(CPF_A))
        .andExpect(jsonPath("$[0].uf").value("MT"))
        .andExpect(jsonPath("$[0].situacaoVinculo").value("ATIVO"))
        .andExpect(jsonPath("$[0].atencao.notasComInconsistencia").value(1))
        .andExpect(jsonPath("$[0].atencao.notasAguardandoAprovacao").value(0))
        .andExpect(jsonPath("$[0].atencao.guiasVencidas").value(2))
        .andExpect(jsonPath("$[0].atencao.vencimentosProximos7Dias").value(1));
  }

  @Test
  @DisplayName("lista: busca por nome sem acento e por CPF completo; filtros e ordenacao")
  void listaBuscaFiltroOrdem() throws Exception {
    clienteA();
    vinculoRepository.save(br.com.agrocontabil.model.VinculoCarteira.builder()
        .escritorioId(escritorioDe(contador.usuarioId())).produtorId(produtorB.usuarioId())
        .situacao(SituacaoVinculo.ATIVO).inicio(java.time.OffsetDateTime.now()).build());
    inserirGuia(produtorB.usuarioId(),
        LocalDate.now(ZoneId.of("America/Sao_Paulo")).minusDays(5), "VENCIDA");

    comoContador("/api/contador/produtores?busca=conceicao")
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].nomeCompleto").value("Joaquim Conceição"));
    comoContador("/api/contador/produtores?busca=529.982.247-25")
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].id").value(produtorA.usuarioId().toString()));
    // CPF parcial nao casa: a coluna e cifrada, so igualdade.
    comoContador("/api/contador/produtores?busca=529982")
        .andExpect(header().string("X-Total-Count", "2"));
    comoContador("/api/contador/produtores?uf=pr")
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].id").value(produtorB.usuarioId().toString()));
    comoContador("/api/contador/produtores?precisaAtencao=true")
        .andExpect(header().string("X-Total-Count", "1"));
    comoContador("/api/contador/produtores")
        .andExpect(jsonPath("$[0].nomeCompleto").value("Beatriz Souza"));
    comoContador("/api/contador/produtores?sort=urgencia&size=1")
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(produtorB.usuarioId().toString()));
    comoContador("/api/contador/produtores?sort=cpfCnpj")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("FILTRO_CARTEIRA_INVALIDO"));
    comoContador("/api/contador/produtores?size=201")
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("ficha: observacoes e etiquetas; erro de tamanho nao ecoa o texto")
  void fichaEtiquetas() throws Exception {
    clienteA();
    mockMvc.perform(patch("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"observacoes\":\"Cliente prefere contato por WhatsApp\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observacoes").value("Cliente prefere contato por WhatsApp"));
    mockMvc.perform(patch("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"observacoes\":\"%s\"}".formatted("x".repeat(1001))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("observacoes"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());

    mockMvc.perform(put("/api/contador/produtores/{id}/etiquetas", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"etiquetas\":[\"Soja\",\"soja\",\"Prioridade\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.etiquetas.length()").value(2))
        .andExpect(jsonPath("$.etiquetas[0]").value("Prioridade"));
    mockMvc.perform(put("/api/contador/produtores/{id}/etiquetas", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"etiquetas\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\","
                + "\"10\",\"11\"]}"))
        .andExpect(status().isBadRequest());

    comoContador("/api/contador/produtores?etiqueta=SOJA")
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].etiquetas[1]").value("Soja"));
    JsonNode catalogo = json(comoContador("/api/contador/etiquetas"));
    assertThat(catalogo).hasSize(2);

    // A ficha nunca aparece para o produtor: o vinculo dele nao traz as observacoes.
    mockMvc.perform(get("/api/vinculos").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(jsonPath("$[0].observacoes").doesNotExist())
        .andExpect(jsonPath("$[0].etiquetas").doesNotExist());

    // Etiqueta de outro escritorio e inexistente (404); a propria sai de todos os clientes.
    cadastroService.cadastrar(new CadastroRequest(PapelUsuario.CONTADOR, "Outro Escritorio",
        "11444777000161", EMAIL_OUTRO_CONTADOR, SENHA));
    String etiquetaId = catalogo.get(0).get("id").asText();
    mockMvc.perform(delete("/api/contador/etiquetas/{id}", etiquetaId)
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_OUTRO_CONTADOR)))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/contador/etiquetas/{id}", etiquetaId)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNoContent());
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(jsonPath("$.etiquetas.length()").value(1));
  }

  @Test
  @DisplayName("ficha e etiquetas de produtor fora da carteira: 404")
  void fichaForaDaCarteira() throws Exception {
    clienteA();
    comoContador("/api/contador/produtores/{id}/ficha", produtorB.usuarioId())
        .andExpect(status().isNotFound());
    mockMvc.perform(put("/api/contador/produtores/{id}/etiquetas", produtorB.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"etiquetas\":[\"X\"]}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("suspender corta o acesso e guarda a ficha; reativar devolve; encerrar e final")
  void cicloDoVinculo() throws Exception {
    clienteA();
    mockMvc.perform(patch("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content("{\"observacoes\":\"guardar\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/contador/produtores/{id}/suspender", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("SUSPENSO"));
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(status().isNotFound());
    comoContador("/api/contador/guias?produtorId={id}", produtorA.usuarioId())
        .andExpect(status().isNotFound());
    comoContador("/api/contador/produtores?situacao=SUSPENSO")
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].atencao").doesNotExist());
    mockMvc.perform(post("/api/contador/produtores/{id}/suspender", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("VINCULO_SITUACAO_INVALIDA"));

    mockMvc.perform(post("/api/contador/produtores/{id}/reativar", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk());
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observacoes").value("guardar"));

    mockMvc.perform(post("/api/contador/produtores/{id}/encerrar", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("ENCERRADO"))
        .andExpect(jsonPath("$.fim").exists());
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/contador/produtores/{id}/reativar", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
    // Produtor fora da carteira (B) nao tem vinculo a suspender.
    mockMvc.perform(post("/api/contador/produtores/{id}/suspender", produtorB.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());

    // Voltar exige novo convite: o aceite cria outro vinculo, sem a ficha antiga.
    clienteA();
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.observacoes").doesNotExist());
    assertThat(vinculoRepository.findIdsDoEscritorio(escritorioDe(contador.usuarioId()))).hasSize(2);

    // Historico: cada vinculo encerrado e uma linha propria (vinculoId), e de quem saiu o
    // escritorio guarda so o nome, sem os dados atuais do produtor.
    mockMvc.perform(post("/api/contador/produtores/{id}/encerrar", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk());
    JsonNode encerrados = json(comoContador("/api/contador/produtores?situacao=ENCERRADO"));
    assertThat(encerrados).hasSize(2);
    assertThat(encerrados.get(0).get("vinculoId").asText())
        .isNotEqualTo(encerrados.get(1).get("vinculoId").asText());
    assertThat(encerrados.get(0).get("nomeCompleto").asText()).isEqualTo("Joaquim Conceição");
    assertThat(encerrados.get(0).get("cpfCnpj").isNull()).isTrue();
    assertThat(encerrados.get(0).get("uf").isNull()).isTrue();
    assertThat(encerrados.get(0).get("regime").isNull()).isTrue();
  }

  @Test
  @DisplayName("produtor ve e encerra o proprio vinculo; o de outro produtor e 404")
  void produtorEncerra() throws Exception {
    clienteA();
    JsonNode vinculos = json(mockMvc.perform(get("/api/vinculos")
        .header(HttpHeaders.AUTHORIZATION, tokenA)));
    assertThat(vinculos).hasSize(1);
    assertThat(vinculos.get(0).get("escritorio").asText()).isEqualTo("Escritorio Campo");
    String vinculoId = vinculos.get(0).get("id").asText();

    mockMvc.perform(get("/api/vinculos").header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(header().string("X-Total-Count", "0"));
    mockMvc.perform(post("/api/vinculos/{id}/encerrar", vinculoId)
            .header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/vinculos").header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/vinculos/{id}/encerrar", vinculoId)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.situacao").value("ENCERRADO"));
    comoContador("/api/contador/produtores/{id}/ficha", produtorA.usuarioId())
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/vinculos/{id}/encerrar", vinculoId)
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("notas do cliente: so as dele, so leitura; nota de B pelo path de A e B: 404")
  void notasDoCliente() throws Exception {
    clienteA();
    inserirNota(produtorA.usuarioId(), 10, "RASCUNHO");
    inserirNota(produtorA.usuarioId(), 11, "COM_INCONSISTENCIAS");
    inserirNota(produtorB.usuarioId(), 12, "RASCUNHO");
    String notaDeB = jdbcTemplate.queryForObject(
        "SELECT CAST(id AS VARCHAR) FROM nota_fiscal WHERE numero = 12", String.class);
    String notaDeA = jdbcTemplate.queryForObject(
        "SELECT CAST(id AS VARCHAR) FROM nota_fiscal WHERE numero = 10", String.class);

    comoContador("/api/contador/produtores/{id}/notas", produtorA.usuarioId())
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$.length()").value(2));
    comoContador("/api/contador/produtores/{id}/notas?status=COM_INCONSISTENCIAS",
        produtorA.usuarioId())
        .andExpect(header().string("X-Total-Count", "1"));
    comoContador("/api/contador/produtores/{id}/notas/{nota}", produtorA.usuarioId(), notaDeA)
        .andExpect(status().isOk());
    comoContador("/api/contador/produtores/{id}/notas/{nota}/validacoes",
        produtorA.usuarioId(), notaDeA)
        .andExpect(status().isOk());
    comoContador("/api/contador/produtores/{id}/notas/{nota}", produtorA.usuarioId(), notaDeB)
        .andExpect(status().isNotFound());
    comoContador("/api/contador/produtores/{id}/notas", produtorB.usuarioId())
        .andExpect(status().isNotFound());
    comoContador("/api/contador/produtores/{id}/notas/{nota}", produtorB.usuarioId(), notaDeB)
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/contador/produtores/{id}/notas", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("auditoria: convite, aceite e transicoes, sem dado pessoal")
  void auditoria() throws Exception {
    clienteA();
    mockMvc.perform(post("/api/contador/produtores/{id}/suspender", produtorA.usuarioId())
        .header(HttpHeaders.AUTHORIZATION, tokenContador)).andExpect(status().isOk());

    List<RegistroAuditoria> doContador = auditoriaRepository
        .findByProdutorId(produtorA.usuarioId(), Pageable.unpaged()).getContent();
    assertThat(doContador).extracting(RegistroAuditoria::getAcao)
        .contains(AcaoAuditada.CONVITE_ACEITO, AcaoAuditada.VINCULO_SUSPENSO);
    assertThat(doContador).filteredOn(r -> r.getAcao() == AcaoAuditada.VINCULO_SUSPENSO)
        .singleElement().satisfies(r -> {
          assertThat(r.getUsuarioId()).isEqualTo(contador.usuarioId());
          assertThat(r.getRecursoId()).isEqualTo(produtorA.usuarioId());
          assertThat(r.getEstadoDepois()).isEqualTo("SUSPENSO");
        });
    Long enviados = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM registro_auditoria"
        + " WHERE acao = 'CONVITE_ENVIADO' AND sucesso = TRUE AND recurso_id IS NOT NULL",
        Long.class);
    assertThat(enviados).isEqualTo(1);
  }
}
