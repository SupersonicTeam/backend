package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pendências e documentos (issue #180) pela cadeia real com JWT: ciclo completo, anexo conferido
 * pelos bytes, download com o MIME conferido, anti-IDOR entre produtores, entre escritórios e fora
 * da carteira, e a pendência no calendário.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Pendencias e documentos (#180)")
class PendenciaIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String BASE = "/api/contador/produtores/{p}/pendencias";
  private static final byte[] PDF = "%PDF-1.7\nconteudo".getBytes(StandardCharsets.US_ASCII);

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse produtorA;
  private CadastroResponse produtorB;
  private String tokenA;
  private String tokenB;
  private String tokenContador;
  private String tokenOutroEscritorio;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    produtorA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor A", "52998224725", "a@fazenda.com.br", SENHA));
    produtorB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Produtor B", "39053344705", "b@fazenda.com.br", SENHA));
    CadastroResponse contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio", "11222333000181", "c@escritorio.com.br", SENHA));
    CadastroResponse outro = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Outro", "11444777000161", "o@escritorio.com.br", SENHA));
    vincular(produtorA.usuarioId(), contador.usuarioId());
    // O outro escritorio tambem atende A: ainda assim nao alcanca as pendencias do primeiro.
    vincular(produtorA.usuarioId(), outro.usuarioId());
    tokenA = token("a@fazenda.com.br");
    tokenB = token("b@fazenda.com.br");
    tokenContador = token("c@escritorio.com.br");
    tokenOutroEscritorio = token("o@escritorio.com.br");
  }

  private void vincular(UUID produtorId, UUID contadorId) {
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, produtor_id, situacao,"
        + " inicio) SELECT RANDOM_UUID(), escritorio_id, ?, 'ATIVO', CURRENT_TIMESTAMP"
        + " FROM contadores WHERE id = ?", produtorId, contadorId);
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private String criar(LocalDate dataLimite) throws Exception {
    String corpo = "{\"titulo\":\"Extrato de setembro\",\"descricao\":\"Conta do Banco X\","
        + (dataLimite == null ? "" : "\"dataLimite\":\"" + dataLimite + "\",")
        + "\"tiposDocumento\":[\"EXTRATO\",\"COMPROVANTE_PAGAMENTO\"]}";
    MvcResult criada = mockMvc.perform(post(BASE, produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDENTE"))
        .andExpect(jsonPath("$.tiposDocumento.length()").value(2))
        .andReturn();
    return objectMapper.readTree(criada.getResponse().getContentAsString()).get("id").asText();
  }

  private MvcResult anexar(String pendenciaId, String nome, String tipoDeclarado, byte[] bytes,
      String token) throws Exception {
    return mockMvc.perform(multipart("/api/pendencias/{id}/anexos", pendenciaId)
            .file(new MockMultipartFile("arquivo", nome, tipoDeclarado, bytes))
            .header(HttpHeaders.AUTHORIZATION, token))
        .andReturn();
  }

  private void transicao(String pendenciaId, String acao, String corpo, int esperado)
      throws Exception {
    var requisicao = post(BASE + "/{id}/" + acao, produtorA.usuarioId(), pendenciaId)
        .header(HttpHeaders.AUTHORIZATION, tokenContador);
    if (corpo != null) {
      requisicao = requisicao.contentType(MediaType.APPLICATION_JSON).content(corpo);
    }
    mockMvc.perform(requisicao).andExpect(status().is(esperado));
  }

  @Test
  @DisplayName("ciclo: criar, anexar, rejeitar, anexar, concluir, reabrir, cancelar; auditado")
  void cicloCompleto() throws Exception {
    String id = criar(null);

    assertThat(anexar(id, "extrato.pdf", "application/pdf", PDF, tokenA).getResponse()
        .getStatus()).isEqualTo(201);
    mockMvc.perform(get(BASE, produtorA.usuarioId()).param("status", "ENVIADA")
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].quantidadeAnexos").value(1));

    transicao(id, "rejeitar", "{\"motivo\":\"Falta a segunda pagina\"}", 200);
    mockMvc.perform(get("/api/pendencias/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDENTE"))
        .andExpect(jsonPath("$.motivo").value("Falta a segunda pagina"))
        .andExpect(jsonPath("$.anexos.length()").value(1));
    // Concluir exige ENVIADA.
    transicao(id, "concluir", null, 409);

    anexar(id, "extrato-2.pdf", "application/pdf", PDF, tokenA);
    transicao(id, "concluir", null, 200);
    // Concluida nao recebe anexo nem edicao.
    assertThat(anexar(id, "x.pdf", "application/pdf", PDF, tokenA).getResponse().getStatus())
        .isEqualTo(409);
    mockMvc.perform(put(BASE + "/{id}", produtorA.usuarioId(), id)
            .header(HttpHeaders.AUTHORIZATION, tokenContador)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"titulo\":\"Novo\",\"tiposDocumento\":[\"OUTRO\"]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("TRANSICAO_PENDENCIA_INVALIDA"));

    transicao(id, "reabrir", null, 200);
    transicao(id, "cancelar", "{\"motivo\":\"ok\"}", 400);
    transicao(id, "cancelar", "{\"motivo\":\"Nao precisa mais\"}", 200);

    assertThat(jdbcTemplate.queryForList("SELECT acao FROM registro_auditoria WHERE recurso_id = ?"
        + " AND sucesso ORDER BY ocorrido_em", String.class, UUID.fromString(id)))
        .contains("PENDENCIA_CRIADA", "PENDENCIA_ANEXO_ENVIADO", "PENDENCIA_REJEITADA",
            "PENDENCIA_CONCLUIDA", "PENDENCIA_REABERTA", "PENDENCIA_CANCELADA");
  }

  @Test
  @DisplayName("anexo: tipo pelos bytes (nunca o declarado), 400 para tipo fora, teto de 10")
  void anexoConferidoPelosBytes() throws Exception {
    String id = criar(null);

    MvcResult recusado = anexar(id, "nota.pdf", "application/pdf",
        "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), tokenA);
    assertThat(recusado.getResponse().getStatus()).isEqualTo(400);
    assertThat(recusado.getResponse().getContentAsString()).contains("ANEXO_INVALIDO");
    assertThat(anexar(id, "app.exe", "application/pdf", new byte[] {'M', 'Z', 0, 0}, tokenA)
        .getResponse().getStatus()).isEqualTo(400);

    // PDF declarado como HTML: guardado e devolvido como PDF, attachment, nome sanitizado.
    MvcResult aceito = anexar(id, "../../segredo.pdf", "text/html", PDF, tokenA);
    assertThat(aceito.getResponse().getStatus()).isEqualTo(201);
    String anexoId = objectMapper.readTree(aceito.getResponse().getContentAsString())
        .get("anexos").get(0).get("id").asText();
    mockMvc.perform(get(BASE + "/{id}/anexos/{a}", produtorA.usuarioId(), id, anexoId)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
            org.hamcrest.Matchers.startsWith("attachment")))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
            org.hamcrest.Matchers.containsString("segredo.pdf")))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(content().bytes(PDF));

    for (int i = 1; i < 10; i++) {
      anexar(id, "p" + i + ".pdf", "application/pdf", PDF, tokenA);
    }
    MvcResult excedeu = anexar(id, "p11.pdf", "application/pdf", PDF, tokenA);
    assertThat(excedeu.getResponse().getStatus()).isEqualTo(409);
    assertThat(excedeu.getResponse().getContentAsString()).contains("LIMITE_ANEXOS");
  }

  @Test
  @DisplayName("anti-IDOR: B nao le nem anexa na de A; outro escritorio e fora da carteira 404")
  void antiIdor() throws Exception {
    String id = criar(null);
    String anexoId = objectMapper.readTree(anexar(id, "e.pdf", "application/pdf", PDF, tokenA)
        .getResponse().getContentAsString()).get("anexos").get(0).get("id").asText();

    mockMvc.perform(get("/api/pendencias/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    assertThat(anexar(id, "b.pdf", "application/pdf", PDF, tokenB).getResponse().getStatus())
        .isEqualTo(404);
    mockMvc.perform(get("/api/pendencias/{id}/anexos/{a}", id, anexoId)
            .header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/pendencias").header(HttpHeaders.AUTHORIZATION, tokenB))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "0"));

    // Outro escritorio que tambem atende A nao ve a pendencia do primeiro.
    mockMvc.perform(get(BASE + "/{id}", produtorA.usuarioId(), id)
            .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(BASE + "/{id}/anexos/{a}", produtorA.usuarioId(), id, anexoId)
            .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(BASE + "/{id}/concluir", produtorA.usuarioId(), id)
            .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio))
        .andExpect(status().isNotFound());
    // Produtor fora da carteira; e o path de B com a pendencia de A.
    mockMvc.perform(get(BASE, produtorB.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(BASE + "/{id}", produtorB.usuarioId(), id)
            .header(HttpHeaders.AUTHORIZATION, tokenContador))
        .andExpect(status().isNotFound());
    // Produtor nas rotas do escritorio: 403.
    mockMvc.perform(get(BASE, produtorA.usuarioId()).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("vinculo nao ativo: o produtor deixa de ver e de anexar na pendencia daquele escritorio")
  void vinculoNaoAtivo() throws Exception {
    String id = criar(null);
    jdbcTemplate.update("UPDATE carteira_vinculo SET situacao = 'ENCERRADO' WHERE produtor_id = ?"
        + " AND escritorio_id = (SELECT escritorio_id FROM contadores"
        + " WHERE nome_completo = 'Escritorio')", produtorA.usuarioId());

    assertThat(anexar(id, "e.pdf", "application/pdf", PDF, tokenA).getResponse().getStatus())
        .isEqualTo(404);
    mockMvc.perform(get("/api/pendencias/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/pendencias").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(header().string("X-Total-Count", "0"));
  }

  @Test
  @DisplayName("reabrir limpa o motivo do cancelamento")
  void reabrirLimpaMotivo() throws Exception {
    String id = criar(null);
    transicao(id, "cancelar", "{\"motivo\":\"Nao precisa mais\"}", 200);
    transicao(id, "reabrir", null, 200);

    mockMvc.perform(get("/api/pendencias/{id}", id).header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(jsonPath("$.status").value("PENDENTE"))
        .andExpect(jsonPath("$.motivo").doesNotExist());
  }

  @Test
  @DisplayName("LGPD: exclusao do produtor apaga pendencias e anexos; a do escritorio, as dele")
  void exclusaoLgpd() throws Exception {
    String id = criar(null);
    anexar(id, "e.pdf", "application/pdf", PDF, tokenA);
    String doOutro = objectMapper.readTree(mockMvc.perform(post(BASE, produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"titulo\":\"Contrato\",\"tiposDocumento\":[\"CONTRATO\"]}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
        .get("id").asText();

    // O outro escritorio (unipessoal) sai: leva so a pendencia dele.
    UUID outroId = jdbcTemplate.queryForObject(
        "SELECT id FROM contadores WHERE nome_completo = 'Outro'", UUID.class);
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/usuarios/{id}/dados", outroId)
            .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio)
            .contentType(MediaType.APPLICATION_JSON).content("{\"senha\":\"" + SENHA + "\"}"))
        .andExpect(status().isNoContent());
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pendencia WHERE id = ?",
        Integer.class, UUID.fromString(doOutro))).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pendencia WHERE id = ?",
        Integer.class, UUID.fromString(id))).isEqualTo(1);

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/usuarios/{id}/dados", produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"senha\":\"" + SENHA + "\"}"))
        .andExpect(status().isNoContent());
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pendencia WHERE produtor_id = ?",
        Integer.class, produtorA.usuarioId())).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pendencia_anexo",
        Integer.class)).isZero();
  }

  @Test
  @DisplayName("calendario: PENDENTE com prazo vira solicitacao; enviada sai do calendario")
  void calendario() throws Exception {
    LocalDate prazo = LocalDate.now().plusDays(3);
    String id = criar(prazo);
    String janela = "?de=" + prazo.minusDays(1) + "&ate=" + prazo.plusDays(1);

    mockMvc.perform(get("/api/calendario-fiscal/{p}/vencimentos" + janela, produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.tipo == 'solicitacao')].id").value(id));
    // O outro escritorio nao ve o pedido do primeiro no calendario do cliente.
    mockMvc.perform(get("/api/calendario-fiscal/{p}/vencimentos" + janela, produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenOutroEscritorio))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.tipo == 'solicitacao')]").isEmpty());

    anexar(id, "e.pdf", "application/pdf", PDF, tokenA);
    mockMvc.perform(get("/api/calendario-fiscal/{p}/vencimentos" + janela, produtorA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(jsonPath("$[?(@.tipo == 'solicitacao')]").isEmpty());
  }
}
