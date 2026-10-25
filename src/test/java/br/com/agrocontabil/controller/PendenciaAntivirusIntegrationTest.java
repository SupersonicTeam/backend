package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.AntivirusIndisponivelException;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.VerificacaoAnexoService;
import br.com.agrocontabil.service.VerificacaoAnexoService.ResumoVerificacao;
import br.com.agrocontabil.service.VerificacaoAntivirus;
import br.com.agrocontabil.service.VerificacaoAntivirus.ResultadoVerificacao;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Antivírus dos anexos das pendências (issue #227) pela cadeia real com JWT, com a porta do
 * antivírus trocada por mock: infectado recusado (422) sem o arquivo no banco e auditado, anexo
 * em verificação sem download, antivírus fora do ar sem liberar nada e o job reverificando quando
 * ele volta. O protocolo do clamd tem testes próprios ({@code ClamdClienteTest} e, com Docker, o
 * {@code ClamdClienteIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Antivirus dos anexos (#227)")
class PendenciaAntivirusIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String BASE = "/api/contador/produtores/{p}/pendencias";
  private static final byte[] PDF = "%PDF-1.7\nconteudo".getBytes(StandardCharsets.US_ASCII);
  /** O arquivo de teste EICAR: texto, não PDF — o antivírus vem antes da conferência do tipo. */
  private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST"
      + "-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private VerificacaoAnexoService verificacao;

  @MockitoBean private VerificacaoAntivirus antivirus;

  private CadastroResponse produtor;
  private String tokenProdutor;
  private String tokenContador;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    produtor = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor A", "52998224725", "a@fazenda.com.br", SENHA));
    CadastroResponse contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio", "11222333000181", "c@escritorio.com.br", SENHA));
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) SELECT RANDOM_UUID(), escritorio_id, ?, 'ATIVO', CURRENT_TIMESTAMP"
        + " FROM contadores WHERE id = ?", produtor.usuarioId(), contador.usuarioId());
    tokenProdutor = token("a@fazenda.com.br");
    tokenContador = token("c@escritorio.com.br");
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private String criar() throws Exception {
    MvcResult criada = mockMvc.perform(post(BASE, produtor.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"titulo\":\"Extrato\",\"tiposDocumento\":[\"EXTRATO\"]}"))
        .andExpect(status().isCreated()).andReturn();
    return objectMapper.readTree(criada.getResponse().getContentAsString()).get("id").asText();
  }

  private MvcResult anexar(String pendenciaId, String nome, byte[] bytes) throws Exception {
    return mockMvc.perform(multipart("/api/pendencias/{id}/anexos", pendenciaId)
            .file(new MockMultipartFile("arquivo", nome, "application/pdf", bytes))
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andReturn();
  }

  private String statusPendencia(String pendenciaId) {
    return jdbcTemplate.queryForObject("SELECT status FROM pendencia WHERE id = ?", String.class,
        UUID.fromString(pendenciaId));
  }

  @Test
  @DisplayName("EICAR: 422 ANEXO_INFECTADO, sem o arquivo no banco, fora da lista e auditado")
  void eicarRecusado() throws Exception {
    when(antivirus.verificar(any())).thenReturn(ResultadoVerificacao.infectado("Eicar-Signature"));
    String id = criar();

    mockMvc.perform(multipart("/api/pendencias/{id}/anexos", id)
            .file(new MockMultipartFile("arquivo", "eicar.com", "text/plain", EICAR))
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("ANEXO_INFECTADO"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("Eicar-Signature"));

    Map<String, Object> registro = jdbcTemplate.queryForMap(
        "SELECT situacao, assinatura_virus, conteudo, sha256, mime FROM pendencia_anexo");
    assertThat(registro.get("SITUACAO")).isEqualTo("INFECTADO");
    assertThat(registro.get("ASSINATURA_VIRUS")).isEqualTo("Eicar-Signature");
    assertThat(registro.get("CONTEUDO")).isNull();
    assertThat((String) registro.get("SHA256")).hasSize(64);
    assertThat(registro.get("MIME")).isEqualTo("application/octet-stream");
    assertThat(statusPendencia(id)).isEqualTo("PENDENTE");

    mockMvc.perform(get(BASE + "/{id}", produtor.usuarioId(), id)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(jsonPath("$.quantidadeAnexos").value(0))
        .andExpect(jsonPath("$.anexos.length()").value(0));
    List<String> acoes = jdbcTemplate.queryForList(
        "SELECT acao FROM registro_auditoria WHERE produtor_id = ? ORDER BY ocorrido_em",
        String.class, produtor.usuarioId());
    assertThat(acoes).contains("PENDENCIA_ANEXO_INFECTADO");
  }

  @Test
  @DisplayName("ameaca registrada mesmo se a pendencia fechou durante a verificacao")
  void infectadoRegistradoComPendenciaFechadaNoMeio() throws Exception {
    String id = criar();
    doAnswer(chamada -> {
      // O contador cancela enquanto o clamd verifica.
      jdbcTemplate.update("UPDATE pendencia SET status = 'CANCELADA' WHERE id = ?",
          UUID.fromString(id));
      return ResultadoVerificacao.infectado("Pdf.Exploit");
    }).when(antivirus).verificar(any());

    mockMvc.perform(multipart("/api/pendencias/{id}/anexos", id)
            .file(new MockMultipartFile("arquivo", "x.pdf", "application/pdf", PDF))
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("ANEXO_INFECTADO"));

    assertThat(jdbcTemplate.queryForObject("SELECT situacao FROM pendencia_anexo", String.class))
        .isEqualTo("INFECTADO");
    assertThat(jdbcTemplate.queryForList(
        "SELECT acao FROM registro_auditoria WHERE acao = 'PENDENCIA_ANEXO_INFECTADO'",
        String.class)).hasSize(1);
    assertThat(statusPendencia(id)).isEqualTo("CANCELADA");
  }

  @Test
  @DisplayName("antivirus chamado fora de transacao e antes do tipo (o parse so ve arquivo limpo)")
  void verificacaoForaDeTransacao() throws Exception {
    when(antivirus.verificar(any())).thenAnswer(chamada -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return ResultadoVerificacao.limpo();
    });
    String id = criar();

    MvcResult resposta = anexar(id, "extrato.pdf", PDF);

    assertThat(resposta.getResponse().getStatus()).isEqualTo(201);
    assertThat(statusPendencia(id)).isEqualTo("ENVIADA");
    // Tipo recusado: o antivirus ja viu o arquivo; o 400 vem da conferencia depois dele.
    assertThat(anexar(id, "x.gif", "GIF89a".getBytes(StandardCharsets.US_ASCII)).getResponse()
        .getStatus()).isEqualTo(400);
  }

  @Test
  @DisplayName("pendencia de outro produtor: 404 sem chamar o antivirus")
  void antiIdorAntesDoAntivirus() throws Exception {
    String id = criar();
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor B", "39053344705", "b@fazenda.com.br", SENHA));

    mockMvc.perform(multipart("/api/pendencias/{id}/anexos", id)
            .file(new MockMultipartFile("arquivo", "x.pdf", "application/pdf", PDF))
            .header(HttpHeaders.AUTHORIZATION, token("b@fazenda.com.br")))
        .andExpect(status().isNotFound());

    verify(antivirus, never()).verificar(any());
  }

  @Test
  @DisplayName("ClamAV fora do ar: 201 EM_VERIFICACAO, sem download, pendencia nao muda; o job"
      + " libera quando ele volta")
  void foraDoArEJobReverifica() throws Exception {
    when(antivirus.verificar(any()))
        .thenThrow(new AntivirusIndisponivelException("recusada", null, true));
    String id = criar();

    MvcResult resposta = anexar(id, "extrato.pdf", PDF);
    assertThat(resposta.getResponse().getStatus()).isEqualTo(201);
    String anexoId = objectMapper.readTree(resposta.getResponse().getContentAsString())
        .at("/anexos/0/id").asText();
    assertThat(objectMapper.readTree(resposta.getResponse().getContentAsString())
        .at("/anexos/0/situacao").asText()).isEqualTo("EM_VERIFICACAO");
    assertThat(statusPendencia(id)).isEqualTo("PENDENTE");

    mockMvc.perform(get(BASE + "/{id}/anexos/{a}", produtor.usuarioId(), id, anexoId)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ANEXO_EM_VERIFICACAO"));
    mockMvc.perform(get("/api/pendencias/{id}/anexos/{a}", id, anexoId)
            .header(HttpHeaders.AUTHORIZATION, tokenProdutor))
        .andExpect(status().isConflict());

    // Ainda fora do ar: o job para o lote e nada muda.
    ResumoVerificacao parado = verificacao.reprocessarPendentes();
    assertThat(parado.antivirusForaDoAr()).isTrue();
    assertThat(statusPendencia(id)).isEqualTo("PENDENTE");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT verificado_em FROM pendencia_anexo WHERE id = ?", Object.class,
        UUID.fromString(anexoId))).isNotNull();

    // Voltou: limpo, a pendencia vai a ENVIADA e o download sai.
    doReturn(ResultadoVerificacao.limpo()).when(antivirus).verificar(any());
    ResumoVerificacao liberado = verificacao.reprocessarPendentes();
    assertThat(liberado.limpos()).isEqualTo(1);
    assertThat(statusPendencia(id)).isEqualTo("ENVIADA");
    mockMvc.perform(get(BASE + "/{id}/anexos/{a}", produtor.usuarioId(), id, anexoId)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(content().bytes(PDF));
  }

  @Test
  @DisplayName("job acha ameaca num anexo em verificacao: descarta o conteudo e audita")
  void jobDescartaInfectado() throws Exception {
    when(antivirus.verificar(any()))
        .thenThrow(new AntivirusIndisponivelException("recusada", null, true));
    String id = criar();
    anexar(id, "extrato.pdf", PDF);

    doReturn(ResultadoVerificacao.infectado("Pdf.Exploit")).when(antivirus).verificar(any());
    ResumoVerificacao resumo = verificacao.reprocessarPendentes();

    assertThat(resumo.infectados()).isEqualTo(1);
    Map<String, Object> registro = jdbcTemplate.queryForMap(
        "SELECT situacao, assinatura_virus, conteudo FROM pendencia_anexo");
    assertThat(registro.get("SITUACAO")).isEqualTo("INFECTADO");
    assertThat(registro.get("ASSINATURA_VIRUS")).isEqualTo("Pdf.Exploit");
    assertThat(registro.get("CONTEUDO")).isNull();
    assertThat(statusPendencia(id)).isEqualTo("PENDENTE");
    assertThat(jdbcTemplate.queryForList(
        "SELECT acao FROM registro_auditoria WHERE acao = 'PENDENCIA_ANEXO_INFECTADO'"
            + " AND produtor_id = ?", String.class, produtor.usuarioId())).hasSize(1);
  }

  @Test
  @DisplayName("ERROR do clamd num anexo nao segura a fila: o seguinte e verificado")
  void recusaDeUmNaoParaOLote() throws Exception {
    when(antivirus.verificar(any()))
        .thenThrow(new AntivirusIndisponivelException("recusada", null, true));
    String id = criar();
    anexar(id, "primeiro.pdf", PDF);
    byte[] segundo = "%PDF-1.7\nsegundo".getBytes(StandardCharsets.US_ASCII);
    anexar(id, "segundo.pdf", segundo);

    doAnswer(chamada -> {
      byte[] bytes = chamada.getArgument(0);
      if (bytes.length == PDF.length) {
        throw new AntivirusIndisponivelException("stream: ERROR", null, false);
      }
      return ResultadoVerificacao.limpo();
    }).when(antivirus).verificar(any());
    ResumoVerificacao resumo = verificacao.reprocessarPendentes();

    assertThat(resumo.antivirusForaDoAr()).isFalse();
    assertThat(resumo.semVeredito()).isEqualTo(1);
    assertThat(resumo.limpos()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForList(
        "SELECT situacao FROM pendencia_anexo ORDER BY nome_arquivo", String.class))
        .containsExactly("EM_VERIFICACAO", "LIMPO");
  }
}
