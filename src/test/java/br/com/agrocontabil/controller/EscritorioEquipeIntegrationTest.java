package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.EnvioConviteEquipe;
import br.com.agrocontabil.support.EscritoriosDeTeste;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Escritório com equipe pela cadeia real, com JWT (issue #228): convite de equipe com token,
 * cadastro e aceite pelo convite, funções, último administrador, responsável por cliente e a
 * fronteira entre dois escritórios (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabaseCleaner.class, EscritorioEquipeIntegrationTest.CapturaConvite.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "app.escritorio.convite.max-por-dia=5")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Escritorio com equipe: convite, funcoes, responsavel e dois escritorios (#228)")
class EscritorioEquipeIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL_ADMIN_A = "admin@escritorio-a.com.br";
  private static final String EMAIL_ADMIN_B = "admin@escritorio-b.com.br";
  private static final String EMAIL_PRODUTOR = "joaquim@fazenda.com.br";
  private static final String EMAIL_NOVO = "assistente@escritorio-a.com.br";

  /** Links de convite de equipe enviados, na ordem. */
  static final BlockingQueue<String> LINKS = new LinkedBlockingQueue<>();

  /** Troca o envio real por uma captura do link. */
  @TestConfiguration
  static class CapturaConvite {
    @Bean
    @Primary
    EnvioConviteEquipe envioConviteEquipeCapturado() {
      return (destinatario, escritorio, link, validade) -> LINKS.add(destinatario + "|" + link);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;

  private CadastroResponse adminA;
  private CadastroResponse adminB;
  private CadastroResponse produtor;
  private String tokenAdminA;
  private String tokenAdminB;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    LINKS.clear();
    adminA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio A", "11222333000181", EMAIL_ADMIN_A, SENHA));
    adminB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Escritorio B", "11444777000161", EMAIL_ADMIN_B, SENHA));
    produtor = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim", "52998224725", EMAIL_PRODUTOR, SENHA));
    // Equipe so no escritorio empresarial (#519); o cadastro curto da o pessoal.
    EscritoriosDeTeste.tornarEmpresarial(jdbcTemplate, adminA.usuarioId());
    EscritoriosDeTeste.tornarEmpresarial(jdbcTemplate, adminB.usuarioId());
    jdbcTemplate.update("INSERT INTO carteira_vinculo (id, escritorio_id, responsavel_id,"
        + " produtor_id, situacao, inicio) SELECT RANDOM_UUID(), escritorio_id, id, ?, 'ATIVO',"
        + " CURRENT_TIMESTAMP FROM contadores WHERE id = ?", produtor.usuarioId(),
        adminA.usuarioId());
    tokenAdminA = token(EMAIL_ADMIN_A);
    tokenAdminB = token(EMAIL_ADMIN_B);
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

  private ResultActions convidarMembro(String token, String email, String funcao)
      throws Exception {
    return mockMvc.perform(post("/api/escritorio/membros/convites")
        .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"funcao\":\"%s\"}".formatted(email, funcao)));
  }

  /** Convida e devolve o token do fragmento do link capturado. */
  private String conviteDeEquipe(String email, String funcao) throws Exception {
    convidarMembro(tokenAdminA, email, funcao).andExpect(status().isAccepted());
    String link = LINKS.poll(5, TimeUnit.SECONDS);
    assertThat(link).startsWith(email + "|").contains("#token=");
    return link.substring(link.indexOf("#token=") + "#token=".length());
  }

  private ResultActions cadastrarPorConvite(String token, String cpf) throws Exception {
    return mockMvc.perform(post("/api/auth/cadastro/equipe").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"%s\",\"nomeCompleto\":\"Ana Assistente\",\"cpfCnpj\":\"%s\","
            .formatted(token, cpf) + "\"senha\":\"%s\"}".formatted(SENHA)));
  }

  /** Um assistente na equipe de A, logado. */
  private String assistenteDeA() throws Exception {
    cadastrarPorConvite(conviteDeEquipe(EMAIL_NOVO, "ASSISTENTE"), "39053344705")
        .andExpect(status().isCreated());
    return token(EMAIL_NOVO);
  }

  private UUID idDe(String email) {
    return jdbcTemplate.queryForObject("SELECT contador_id FROM credencial_usuario"
        + " WHERE papel = 'CONTADOR' AND contador_id IN (SELECT id FROM contadores"
        + " WHERE nome_completo = 'Ana Assistente')", UUID.class);
  }

  @Test
  @DisplayName("cadastro de contador cria o escritorio unipessoal, com ele administrador")
  void cadastroCriaUnipessoal() throws Exception {
    mockMvc.perform(get("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nome").value("Escritorio A"))
        .andExpect(jsonPath("$.minhaFuncao").value("ADMINISTRADOR"))
        .andExpect(jsonPath("$.membros").value(1));
    assertThat(escritorioDe(adminA.usuarioId())).isNotEqualTo(escritorioDe(adminB.usuarioId()));
    mockMvc.perform(get("/api/escritorio").header(HttpHeaders.AUTHORIZATION, token(EMAIL_PRODUTOR)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("convite de equipe: 202 sempre; cadastro pelo link entra na equipe com a funcao")
  void cadastroPeloConvite() throws Exception {
    convidarMembro(tokenAdminA, "ninguem@lugar.nenhum", "CONTADOR")
        .andExpect(status().isAccepted()).andExpect(jsonPath("$").doesNotExist());
    LINKS.poll(5, TimeUnit.SECONDS);
    String tokenAssistente = assistenteDeA();

    mockMvc.perform(get("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAssistente))
        .andExpect(jsonPath("$.id").value(escritorioDe(adminA.usuarioId()).toString()))
        .andExpect(jsonPath("$.minhaFuncao").value("ASSISTENTE"))
        .andExpect(jsonPath("$.membros").value(2));
    // Todo membro ve a carteira inteira do escritorio.
    mockMvc.perform(get("/api/contador/produtores").header(HttpHeaders.AUTHORIZATION,
            tokenAssistente))
        .andExpect(header().string("X-Total-Count", "1"));
    mockMvc.perform(get("/api/escritorio/membros").header(HttpHeaders.AUTHORIZATION,
            tokenAssistente))
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$[*].email").doesNotExist());
    // O cadastro pelo convite nao criou escritorio unipessoal.
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio", Integer.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("convite com CNPJ no documento (#519): 400 no cpfCnpj, convite segue valendo")
  void cadastroPorConviteSoComCpf() throws Exception {
    String tokenConvite = conviteDeEquipe(EMAIL_NOVO, "CONTADOR");

    cadastrarPorConvite(tokenConvite, "48315276000195")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("cpfCnpj"))
        .andExpect(jsonPath("$.errors[0].message").value(
            org.hamcrest.Matchers.containsString("aba Equipe")));
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM convite_membro WHERE usado_em IS NULL", Integer.class)).isEqualTo(1);

    cadastrarPorConvite(tokenConvite, "39053344705").andExpect(status().isCreated());
  }

  @Test
  @DisplayName("convite com CRC, telefone e aceite dos termos (#519): gravados; aceite false e 400")
  void cadastroPorConviteComCamposNovos() throws Exception {
    String tokenConvite = conviteDeEquipe(EMAIL_NOVO, "CONTADOR");
    String corpo = "{\"token\":\"%s\",\"nomeCompleto\":\"Ana Assistente\","
        + "\"cpfCnpj\":\"39053344705\",\"senha\":\"%s\",\"crc\":\"PR-099/O\","
        + "\"telefone\":\"45988887777\",\"aceiteTermos\":%s}";

    mockMvc.perform(post("/api/auth/cadastro/equipe").contentType(MediaType.APPLICATION_JSON)
            .content(corpo.formatted(tokenConvite, SENHA, "false")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("aceiteTermos"));
    mockMvc.perform(post("/api/auth/cadastro/equipe").contentType(MediaType.APPLICATION_JSON)
            .content(corpo.formatted(tokenConvite, SENHA, "true")))
        .andExpect(status().isCreated());

    UUID novo = idDe(EMAIL_NOVO);
    assertThat(jdbcTemplate.queryForObject("SELECT crc FROM contadores WHERE id = ?",
        String.class, novo)).isEqualTo("PR-099/O");
    assertThat(jdbcTemplate.queryForObject("SELECT telefone FROM contadores WHERE id = ?",
        String.class, novo)).startsWith("v1:");
    assertThat(jdbcTemplate.queryForObject("SELECT termos_versao FROM credencial_usuario"
        + " WHERE contador_id = ?", String.class, novo)).isEqualTo("2026-11-11");
    assertThat(escritorioDe(novo)).isEqualTo(escritorioDe(adminA.usuarioId()));
  }

  @Test
  @DisplayName("token usado, vencido ou inventado: 400 CONVITE_EQUIPE_INVALIDO")
  void tokenInvalido() throws Exception {
    String tokenConvite = conviteDeEquipe(EMAIL_NOVO, "CONTADOR");
    cadastrarPorConvite(tokenConvite, "39053344705").andExpect(status().isCreated());

    cadastrarPorConvite(tokenConvite, "86288366757")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("CONVITE_EQUIPE_INVALIDO"));
    cadastrarPorConvite("x".repeat(43), "86288366757")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("CONVITE_EQUIPE_INVALIDO"));

    String vencido = conviteDeEquipe("outro@escritorio-a.com.br", "CONTADOR");
    jdbcTemplate.update("UPDATE convite_membro SET expira_em = DATEADD('DAY', -1,"
        + " CURRENT_TIMESTAMP) WHERE usado_em IS NULL");
    cadastrarPorConvite(vencido, "86288366757")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("CONVITE_EQUIPE_INVALIDO"));
  }

  @Test
  @DisplayName("aceite com conta: so o mesmo e-mail; unipessoal vazio sai, com clientes e 409")
  void aceiteComConta() throws Exception {
    String tokenParaB = conviteDeEquipe(EMAIL_ADMIN_B, "CONTADOR");
    // Outro contador com o link: e-mail diferente do convite.
    mockMvc.perform(post("/api/escritorio/membros/convites/aceitar")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\"}".formatted(tokenParaB)))
        .andExpect(status().isBadRequest());
    UUID escritorioAntigoDeB = escritorioDe(adminB.usuarioId());

    mockMvc.perform(post("/api/escritorio/membros/convites/aceitar")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\"}".formatted(tokenParaB)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.funcao").value("CONTADOR"));

    assertThat(escritorioDe(adminB.usuarioId())).isEqualTo(escritorioDe(adminA.usuarioId()));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio WHERE id = ?",
        Integer.class, escritorioAntigoDeB)).isZero();
  }

  @Test
  @DisplayName("aceite com escritorio atual em uso (tem clientes): 409, nada muda")
  void aceiteComEscritorioEmUso() throws Exception {
    // A convida B, mas e B quem tem cliente agora.
    jdbcTemplate.update("UPDATE carteira_vinculo SET escritorio_id = ?",
        escritorioDe(adminB.usuarioId()));
    String tokenParaB = conviteDeEquipe(EMAIL_ADMIN_B, "CONTADOR");

    mockMvc.perform(post("/api/escritorio/membros/convites/aceitar")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\"}".formatted(tokenParaB)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_ATUAL_EM_USO"));
    assertThat(escritorioDe(adminB.usuarioId())).isNotEqualTo(escritorioDe(adminA.usuarioId()));
  }

  @Test
  @DisplayName("assistente atende, mas nao gerencia carteira, catalogo nem equipe (403)")
  void funcoes() throws Exception {
    String assistente = assistenteDeA();
    UUID produtorId = produtor.usuarioId();

    mockMvc.perform(get("/api/contador/produtores/{id}/ficha", produtorId)
            .header(HttpHeaders.AUTHORIZATION, assistente))
        .andExpect(status().isOk());
    mockMvc.perform(patch("/api/contador/produtores/{id}/ficha", produtorId)
            .header(HttpHeaders.AUTHORIZATION, assistente).contentType(MediaType.APPLICATION_JSON)
            .content("{\"observacoes\":\"ok\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/contador/produtores/{id}/suspender", produtorId)
            .header(HttpHeaders.AUTHORIZATION, assistente))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("ASSISTENTE"));
    mockMvc.perform(post("/api/contador/convites").header(HttpHeaders.AUTHORIZATION, assistente)
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@y.com\"}"))
        .andExpect(status().isForbidden());
    convidarMembro(assistente, "x@y.com", "CONTADOR").andExpect(status().isForbidden());
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, assistente)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Outro\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("ultimo administrador nao e rebaixado nem removido; promovido outro, pode")
  void ultimoAdministrador() throws Exception {
    assistenteDeA();
    UUID assistenteId = idDe(EMAIL_NOVO);

    mockMvc.perform(patch("/api/escritorio/membros/{id}", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"funcao\":\"CONTADOR\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ULTIMO_ADMINISTRADOR"));
    mockMvc.perform(delete("/api/escritorio/membros/{id}", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA))
        .andExpect(status().isConflict());
    // LGPD: o ultimo administrador de uma equipe nao exclui os dados sem transferir.
    mockMvc.perform(delete("/api/usuarios/{id}/dados", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"senha\":\"%s\"}".formatted(SENHA)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ULTIMO_ADMINISTRADOR"));

    mockMvc.perform(patch("/api/escritorio/membros/{id}", assistenteId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"funcao\":\"ADMINISTRADOR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.funcao").value("ADMINISTRADOR"));
    mockMvc.perform(patch("/api/escritorio/membros/{id}", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"funcao\":\"CONTADOR\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("membro removido perde a carteira; os clientes dele ficam sem responsavel")
  void remocao() throws Exception {
    String assistente = assistenteDeA();
    UUID assistenteId = idDe(EMAIL_NOVO);
    jdbcTemplate.update("UPDATE carteira_vinculo SET responsavel_id = ?", assistenteId);

    mockMvc.perform(delete("/api/escritorio/membros/{id}", assistenteId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/contador/produtores").header(HttpHeaders.AUTHORIZATION, assistente))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].rejectedValue").value("SEM_ESCRITORIO"));
    mockMvc.perform(get("/api/contador/produtores?responsavel=nenhum")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA))
        .andExpect(header().string("X-Total-Count", "1"));
  }

  @Test
  @DisplayName("responsavel: admin repassa a membro; de outro escritorio 404; assistente nao (403)")
  void responsavel() throws Exception {
    String assistente = assistenteDeA();
    UUID assistenteId = idDe(EMAIL_NOVO);
    UUID produtorId = produtor.usuarioId();

    mockMvc.perform(patch("/api/contador/produtores/{id}/responsavel", produtorId)
            .header(HttpHeaders.AUTHORIZATION, assistente).contentType(MediaType.APPLICATION_JSON)
            .content("{\"responsavelId\":\"%s\"}".formatted(assistenteId)))
        .andExpect(status().isForbidden());
    mockMvc.perform(patch("/api/contador/produtores/{id}/responsavel", produtorId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"responsavelId\":\"%s\"}".formatted(adminB.usuarioId())))
        .andExpect(status().isNotFound());
    mockMvc.perform(patch("/api/contador/produtores/{id}/responsavel", produtorId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"responsavelId\":\"%s\"}".formatted(assistenteId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responsavelNome").value("Ana Assistente"));

    mockMvc.perform(get("/api/contador/produtores?responsavel=eu")
            .header(HttpHeaders.AUTHORIZATION, assistente))
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].responsavelId").value(assistenteId.toString()));
    // Agora responsavel, o assistente repassa (devolve sem responsavel).
    mockMvc.perform(patch("/api/contador/produtores/{id}/responsavel", produtorId)
            .header(HttpHeaders.AUTHORIZATION, assistente).contentType(MediaType.APPLICATION_JSON)
            .content("{\"responsavelId\":null}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("dois escritorios: B nao alcanca cliente, membros nem etiquetas de A (404)")
  void doisEscritorios() throws Exception {
    UUID produtorId = produtor.usuarioId();
    mockMvc.perform(get("/api/contador/produtores/{id}/ficha", produtorId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/contador/produtores/{id}/notas", produtorId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/contador/produtores/{id}/suspender", produtorId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB))
        .andExpect(status().isNotFound());
    mockMvc.perform(patch("/api/contador/produtores/{id}/responsavel", produtorId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB).contentType(MediaType.APPLICATION_JSON)
            .content("{\"responsavelId\":null}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/contador/guias?produtorId={id}", produtorId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB))
        .andExpect(status().isNotFound());
    mockMvc.perform(patch("/api/escritorio/membros/{id}", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB).contentType(MediaType.APPLICATION_JSON)
            .content("{\"funcao\":\"ASSISTENTE\"}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/escritorio/membros/{id}", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/escritorio/membros").header(HttpHeaders.AUTHORIZATION, tokenAdminB))
        .andExpect(header().string("X-Total-Count", "1"))
        .andExpect(jsonPath("$[0].id").value(adminB.usuarioId().toString()));
  }

  @Test
  @DisplayName("convite do proprio escritorio nao muda funcao: o admin unico nao se rebaixa (400)")
  void aceiteNoMesmoEscritorio() throws Exception {
    String tokenProprio = conviteDeEquipe(EMAIL_ADMIN_A, "ASSISTENTE");

    mockMvc.perform(post("/api/escritorio/membros/convites/aceitar")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\"}".formatted(tokenProprio)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("CONVITE_EQUIPE_INVALIDO"));

    assertThat(jdbcTemplate.queryForObject("SELECT funcao_escritorio FROM contadores WHERE id = ?",
        String.class, adminA.usuarioId())).isEqualTo("ADMINISTRADOR");
  }

  @Test
  @DisplayName("convite de equipe: reenvio em 24 h nao manda outro e-mail; teto com Retry-After")
  void reenvioETetoDeEquipe() throws Exception {
    convidarMembro(tokenAdminA, EMAIL_NOVO, "CONTADOR").andExpect(status().isAccepted());
    convidarMembro(tokenAdminA, EMAIL_NOVO, "CONTADOR").andExpect(status().isAccepted());
    assertThat(LINKS.poll(5, TimeUnit.SECONDS)).isNotNull();
    assertThat(LINKS.poll(1, TimeUnit.SECONDS)).isNull();

    for (int i = 0; i < 4; i++) {
      convidarMembro(tokenAdminA, "m" + i + "@x.com", "CONTADOR")
          .andExpect(status().isAccepted());
    }
    jdbcTemplate.update("UPDATE convite_membro SET criado_em = DATEADD('HOUR', -22,"
        + " CURRENT_TIMESTAMP)");
    String espera = convidarMembro(tokenAdminA, "sexto@x.com", "CONTADOR")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errors[0].code").value("LIMITE_CONVITES"))
        .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);
    assertThat(Long.parseLong(espera)).isBetween(7000L, 7300L);
  }

  @Test
  @DisplayName("convite vencido nao bloqueia o reenvio, mesmo dentro de 24 h")
  void reenvioDeConviteVencido() throws Exception {
    convidarMembro(tokenAdminA, EMAIL_NOVO, "CONTADOR").andExpect(status().isAccepted());
    assertThat(LINKS.poll(5, TimeUnit.SECONDS)).isNotNull();
    jdbcTemplate.update("UPDATE convite_membro SET expira_em = DATEADD('MINUTE', -1,"
        + " CURRENT_TIMESTAMP)");

    convidarMembro(tokenAdminA, EMAIL_NOVO, "CONTADOR").andExpect(status().isAccepted());

    assertThat(LINKS.poll(5, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  @DisplayName("PATCH do escritorio: CNPJ ausente fica; vazio e 422 no empresarial e apaga no pessoal")
  void patchDoEscritorio() throws Exception {
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Escritorio A\",\"cnpj\":\"11222333000181\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cnpj").value("11222333000181"));
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Novo Nome\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nome").value("Novo Nome"))
        .andExpect(jsonPath("$.cnpj").value("11222333000181"));
    // Empresarial nao fica sem CNPJ (#519).
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Novo Nome\",\"cnpj\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_EMPRESARIAL_EXIGE_CNPJ"));
    // No pessoal, vazio apaga, como antes da #519.
    jdbcTemplate.update("UPDATE escritorio SET tipo = 'PESSOAL' WHERE id = ?",
        escritorioDe(adminA.usuarioId()));
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Novo Nome\",\"cnpj\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cnpj").doesNotExist());

    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM registro_auditoria"
        + " WHERE acao = 'ESCRITORIO_ALTERADO' AND sucesso = TRUE AND recurso_id = ?",
        Integer.class, escritorioDe(adminA.usuarioId()))).isEqualTo(3);
  }

  private static final String ENDERECO = """
      {"cep":"85810000","logradouro":"Rua Paraná","numero":"1200","complemento":"Sala 3",\
      "bairro":"Centro","codigoMunicipioIbge":"4104808","municipio":"Cascavel","uf":"PR"}""";

  /** Um contador com escritório pessoal (o cadastro curto), logado. */
  private String administradorPessoal(String email, String cpf) throws Exception {
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Pessoal " + cpf, cpf, email, SENHA));
    return token(email);
  }

  @Test
  @DisplayName("escritorio pessoal (#519): convite de equipe e 409 ESCRITORIO_PESSOAL, sem e-mail")
  void pessoalNaoConvida() throws Exception {
    String tokenPessoal = administradorPessoal("pessoal@contador.com.br", "86288366757");

    convidarMembro(tokenPessoal, "alguem@lugar.com.br", "CONTADOR")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_PESSOAL"))
        .andExpect(jsonPath("$.errors[0].suggestion").value(
            org.hamcrest.Matchers.containsString("tornar-empresarial")));
    assertThat(LINKS.poll(1, TimeUnit.SECONDS)).isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM convite_membro", Integer.class))
        .isZero();
  }

  @Test
  @DisplayName("convite pendente de escritorio que ficou pessoal (#519): 400 no aceite e no cadastro")
  void convitePendenteDePessoalNaoVale() throws Exception {
    String paraB = conviteDeEquipe(EMAIL_ADMIN_B, "CONTADOR");
    String paraNovo = conviteDeEquipe(EMAIL_NOVO, "ASSISTENTE");
    // Convite gravado antes do tipo: o escritorio de A e pessoal pelo changeSet 0062.
    jdbcTemplate.update("UPDATE escritorio SET tipo = 'PESSOAL' WHERE id = ?",
        escritorioDe(adminA.usuarioId()));

    mockMvc.perform(post("/api/escritorio/membros/convites/aceitar")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminB).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\"}".formatted(paraB)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("CONVITE_EQUIPE_INVALIDO"));
    cadastrarPorConvite(paraNovo, "39053344705")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("CONVITE_EQUIPE_INVALIDO"));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contadores WHERE escritorio_id = ?",
        Integer.class, escritorioDe(adminA.usuarioId()))).isOne();

    // Tornado empresarial, os mesmos convites voltam a valer.
    jdbcTemplate.update("UPDATE escritorio SET tipo = 'EMPRESARIAL' WHERE id = ?",
        escritorioDe(adminA.usuarioId()));
    cadastrarPorConvite(paraNovo, "39053344705").andExpect(status().isCreated());
  }

  @Test
  @DisplayName("tornar empresarial (#519): pessoal vira empresarial, auditado; depois convida")
  void tornarEmpresarial() throws Exception {
    String tokenPessoal = administradorPessoal("pessoal@contador.com.br", "86288366757");
    String corpo = """
        {"nome":" Contabilidade Campo Verde ","cnpj":"48315276000195","telefone":"4533330000",\
        "endereco":%s}""".formatted(ENDERECO);

    mockMvc.perform(post("/api/escritorio/tornar-empresarial")
            .header(HttpHeaders.AUTHORIZATION, tokenPessoal).contentType(MediaType.APPLICATION_JSON)
            .content(corpo))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tipo").value("EMPRESARIAL"))
        .andExpect(jsonPath("$.nome").value("Contabilidade Campo Verde"))
        .andExpect(jsonPath("$.cnpj").value("48315276000195"))
        .andExpect(jsonPath("$.telefone").value("4533330000"))
        .andExpect(jsonPath("$.endereco.municipio").value("Cascavel"))
        .andExpect(jsonPath("$.slug").doesNotExist());
    assertThat(jdbcTemplate.queryForList("SELECT estado_antes || '>' || estado_depois FROM"
        + " registro_auditoria WHERE acao = 'ESCRITORIO_TORNADO_EMPRESARIAL' AND sucesso = TRUE",
        String.class)).containsExactly("PESSOAL>EMPRESARIAL");

    convidarMembro(tokenPessoal, "membro@campoverde.com.br", "CONTADOR")
        .andExpect(status().isAccepted());
    mockMvc.perform(post("/api/escritorio/tornar-empresarial")
            .header(HttpHeaders.AUTHORIZATION, tokenPessoal).contentType(MediaType.APPLICATION_JSON)
            .content(corpo))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_JA_EMPRESARIAL"));
  }

  @Test
  @DisplayName("tornar empresarial (#519): dados invalidos 400, assistente 403, nada muda")
  void tornarEmpresarialRecusado() throws Exception {
    String tokenPessoal = administradorPessoal("pessoal@contador.com.br", "86288366757");

    mockMvc.perform(post("/api/escritorio/tornar-empresarial")
            .header(HttpHeaders.AUTHORIZATION, tokenPessoal).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Campo Verde\",\"telefone\":\"4533330000\",\"endereco\":%s}"
                .formatted(ENDERECO)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("cnpj"));
    mockMvc.perform(post("/api/escritorio/tornar-empresarial")
            .header(HttpHeaders.AUTHORIZATION, tokenPessoal).contentType(MediaType.APPLICATION_JSON)
            .content(("{\"nome\":\"Campo Verde\",\"cnpj\":\"48315276000195\","
                + "\"telefone\":\"4533330000\",\"endereco\":%s}")
                .formatted(ENDERECO.replace("4104808", "5103403"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("endereco.codigoMunicipioIbge"));
    mockMvc.perform(post("/api/escritorio/tornar-empresarial")
            .header(HttpHeaders.AUTHORIZATION, assistenteDeA()).contentType(MediaType.APPLICATION_JSON)
            .content(("{\"nome\":\"Campo Verde\",\"cnpj\":\"48315276000195\","
                + "\"telefone\":\"4533330000\",\"endereco\":%s}").formatted(ENDERECO)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));

    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio WHERE tipo = 'PESSOAL'",
        Integer.class)).isOne();
  }

  @Test
  @DisplayName("PATCH com telefone e endereco (#519): gravados; o que nao vem ou vem vazio fica")
  void patchTelefoneEEndereco() throws Exception {
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Escritorio A\",\"telefone\":\"4533330000\",\"endereco\":%s}"
                .formatted(ENDERECO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.telefone").value("4533330000"))
        .andExpect(jsonPath("$.endereco.logradouro").value("Rua Paraná"));
    assertThat(jdbcTemplate.queryForObject("SELECT telefone FROM escritorio WHERE id = ?",
        String.class, escritorioDe(adminA.usuarioId()))).startsWith("v1:");

    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Escritorio A Ltda\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.telefone").value("4533330000"))
        .andExpect(jsonPath("$.endereco.municipio").value("Cascavel"));

    // Vazio = nao informado, como no cadastro: o telefone fica.
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Escritorio A\",\"telefone\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.telefone").value("4533330000"));
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Escritorio A\",\"telefone\":\"4599\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("telefone"));
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenAdminA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Escritorio A\",\"endereco\":%s}"
                .formatted(ENDERECO.replace("\"Centro\"", "\" \""))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("endereco.bairro"));
  }

  @Test
  @DisplayName("PATCH no pessoal (#519): telefone ou endereco e 409; nome, CNPJ e telefone vazio passam")
  void patchNoPessoal() throws Exception {
    String tokenPessoal = administradorPessoal("pessoal@contador.com.br", "86288366757");

    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenPessoal)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Pessoal\",\"telefone\":\"4533330000\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_PESSOAL"))
        .andExpect(jsonPath("$.errors[0].suggestion").value(
            org.hamcrest.Matchers.containsString("tornar-empresarial")));
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenPessoal)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Pessoal\",\"endereco\":%s}".formatted(ENDERECO)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_PESSOAL"));
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenPessoal)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Pessoal Renomeado\",\"cnpj\":\"48315276000195\","
                + "\"telefone\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nome").value("Pessoal Renomeado"))
        .andExpect(jsonPath("$.cnpj").value("48315276000195"))
        .andExpect(jsonPath("$.tipo").value("PESSOAL"))
        .andExpect(jsonPath("$.telefone").doesNotExist())
        .andExpect(jsonPath("$.endereco").doesNotExist());
  }

  @Test
  @DisplayName("convites pendentes do pessoal (#519): lista vazia; tornado empresarial, eles voltam")
  void convitesPendentesDoPessoal() throws Exception {
    conviteDeEquipe(EMAIL_NOVO, "ASSISTENTE");
    jdbcTemplate.update("UPDATE escritorio SET tipo = 'PESSOAL' WHERE id = ?",
        escritorioDe(adminA.usuarioId()));

    mockMvc.perform(get("/api/escritorio/membros/convites")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "0"))
        .andExpect(jsonPath("$.length()").value(0));

    jdbcTemplate.update("UPDATE escritorio SET tipo = 'EMPRESARIAL' WHERE id = ?",
        escritorioDe(adminA.usuarioId()));
    mockMvc.perform(get("/api/escritorio/membros/convites")
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "1"));
  }

  @Test
  @DisplayName("auditoria guarda o escritorio de quem agiu")
  void auditoriaComEscritorio() throws Exception {
    assistenteDeA();
    UUID assistenteId = idDe(EMAIL_NOVO);
    mockMvc.perform(patch("/api/escritorio/membros/{id}", assistenteId)
            .header(HttpHeaders.AUTHORIZATION, tokenAdminA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"funcao\":\"CONTADOR\"}"))
        .andExpect(status().isOk());

    UUID escritorio = jdbcTemplate.queryForObject("SELECT escritorio_id FROM registro_auditoria"
        + " WHERE acao = 'FUNCAO_ALTERADA' AND sucesso = TRUE", UUID.class);
    assertThat(escritorio).isEqualTo(escritorioDe(adminA.usuarioId()));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM registro_auditoria"
        + " WHERE acao = 'MEMBRO_CONVIDADO' AND recurso_id IS NOT NULL", Integer.class))
        .isEqualTo(1);
  }
}
