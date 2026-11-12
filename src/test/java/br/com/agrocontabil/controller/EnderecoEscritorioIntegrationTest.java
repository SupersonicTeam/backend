package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.model.MetodoVerificacaoCnpj;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.service.EnvioConviteEquipe;
import br.com.agrocontabil.service.VerificacaoCnpjService;
import br.com.agrocontabil.support.EscritoriosDeTeste;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Endereço próprio do escritório pela cadeia real, com JWT (issue #464): definir, trocar e remover
 * o slug, a carência, o CNPJ, a auditoria sem o texto do slug, o aceite de equipe de um unipessoal
 * com endereço e a exclusão de dados.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabaseCleaner.class, EnderecoEscritorioIntegrationTest.CapturaConvite.class})
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Endereco proprio do escritorio: slug, carencia, CNPJ, auditoria e LGPD (#464)")
class EnderecoEscritorioIntegrationTest {

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL_A = "admin@rural-oeste.com.br";
  private static final String EMAIL_B = "admin@campo-verde.com.br";
  private static final String EMAIL_PRODUTOR = "joaquim@fazenda.com.br";
  private static final String CNPJ_A = "11222333000181";
  private static final String CNPJ_B = "11444777000161";

  /** Links de convite de equipe enviados, na ordem. */
  static final BlockingQueue<String> LINKS = new LinkedBlockingQueue<>();

  /** Troca o envio real por uma captura do link. */
  @TestConfiguration
  static class CapturaConvite {
    @Bean
    @Primary
    EnvioConviteEquipe envioConviteEquipeCapturadoEndereco() {
      return (destinatario, escritorio, link, validade) -> LINKS.add(destinatario + "|" + link);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private VerificacaoCnpjService verificacaoCnpj;

  private CadastroResponse adminA;
  private CadastroResponse adminB;
  private String tokenA;
  private String tokenB;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    LINKS.clear();
    adminA = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Rural Oeste", "52998224725", EMAIL_A, SENHA));
    adminB = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Campo Verde", "39053344705", EMAIL_B, SENHA));
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim", "86288366757", EMAIL_PRODUTOR, SENHA));
    jdbcTemplate.update("UPDATE escritorio SET cnpj = ? WHERE id = ?", CNPJ_A,
        escritorioDe(adminA.usuarioId()));
    jdbcTemplate.update("UPDATE escritorio SET cnpj = ? WHERE id = ?", CNPJ_B,
        escritorioDe(adminB.usuarioId()));
    // Endereco proprio so no escritorio empresarial (#519); o cadastro curto da o pessoal.
    EscritoriosDeTeste.tornarEmpresarial(jdbcTemplate, adminA.usuarioId());
    EscritoriosDeTeste.tornarEmpresarial(jdbcTemplate, adminB.usuarioId());
    tokenA = token(EMAIL_A);
    tokenB = token(EMAIL_B);
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

  /** O administrador de B convida o e-mail para a equipe dele; devolve o token do link. */
  private String conviteDeBPara(String email) throws Exception {
    mockMvc.perform(post("/api/escritorio/membros/convites")
            .header(HttpHeaders.AUTHORIZATION, tokenB).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"funcao\":\"CONTADOR\"}".formatted(email)))
        .andExpect(status().isAccepted());
    String link = LINKS.poll(5, TimeUnit.SECONDS);
    assertThat(link).startsWith(email + "|");
    return link.substring(link.indexOf("#token=") + "#token=".length());
  }

  private ResultActions definir(String token, String slug) throws Exception {
    return mockMvc.perform(put("/api/escritorio/endereco").header(HttpHeaders.AUTHORIZATION, token)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"slug\":\"%s\",\"cienteExposicaoPublica\":true}".formatted(slug)));
  }

  private ResultActions remover(String token) throws Exception {
    return mockMvc.perform(delete("/api/escritorio/endereco")
        .header(HttpHeaders.AUTHORIZATION, token));
  }

  /** Volta a última alteração do slug no tempo, para passar do intervalo de 30 dias. */
  private void passarIntervalo(CadastroResponse admin) {
    jdbcTemplate.update("UPDATE escritorio SET slug_alterado_em = DATEADD('DAY', -31,"
        + " CURRENT_TIMESTAMP) WHERE id = ?", escritorioDe(admin.usuarioId()));
  }

  private JsonNode primeiroErro(ResultActions resposta) throws Exception {
    return objectMapper.readTree(resposta.andReturn().getResponse().getContentAsString())
        .get("errors").get(0);
  }

  @Test
  @DisplayName("define o endereco: 200 com slug e host; todo membro ve; auditoria sem o texto do slug")
  void defineEAudita() throws Exception {
    mockMvc.perform(get("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(jsonPath("$.slug").doesNotExist())
        .andExpect(jsonPath("$.enderecoHost").doesNotExist());

    definir(tokenA, "  RuralOeste ")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("ruraloeste"))
        .andExpect(jsonPath("$.enderecoHost").value("ruraloeste.agroconia.com.br"))
        .andExpect(jsonPath("$.enderecoAlteravelEm").isNotEmpty())
        .andExpect(jsonPath("$.minhaFuncao").value("ADMINISTRADOR"));

    mockMvc.perform(get("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(jsonPath("$.slug").value("ruraloeste"));
    UUID escritorio = escritorioDe(adminA.usuarioId());
    assertThat(jdbcTemplate.queryForObject("SELECT slug FROM escritorio WHERE id = ?",
        String.class, escritorio)).isEqualTo("ruraloeste");

    assertThat(jdbcTemplate.queryForList("SELECT estado_antes, estado_depois FROM"
        + " registro_auditoria WHERE acao = 'ESCRITORIO_ENDERECO_ALTERADO' AND sucesso = TRUE"
        + " AND recurso_id = ?", escritorio))
        .singleElement()
        .satisfies(linha -> {
          assertThat(linha.get("ESTADO_ANTES")).isEqualTo("SEM_ENDERECO");
          assertThat(linha.get("ESTADO_DEPOIS")).isEqualTo("COM_ENDERECO");
        });
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM registro_auditoria"
        + " WHERE LOWER(COALESCE(estado_antes, '') || COALESCE(estado_depois, '')"
        + " || COALESCE(codigo_erro, '')) LIKE '%ruraloeste%'", Integer.class)).isZero();
  }

  @Test
  @DisplayName("forma invalida: 400 SLUG_INVALIDO; sem a ciencia: 400 VALIDATION_ERROR")
  void validacao() throws Exception {
    for (String slug : new String[] {"Rural Oeste", "xn--rural", "ab", "2026", "rural--oeste"}) {
      definir(tokenA, slug)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].code").value("SLUG_INVALIDO"))
          .andExpect(jsonPath("$.errors[0].field").value("slug"));
    }
    mockMvc.perform(put("/api/escritorio/endereco").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slug\":\"ruraloeste\",\"cienteExposicaoPublica\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("cienteExposicaoPublica"));
    mockMvc.perform(put("/api/escritorio/endereco").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"ruraloeste\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("cienteExposicaoPublica"));
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio WHERE slug IS NOT"
        + " NULL", Integer.class)).isZero();
  }

  @Test
  @DisplayName("reservado, termo proibido ou em uso: o mesmo 409 SLUG_INDISPONIVEL, sem eco do slug")
  void indisponivelIgual() throws Exception {
    definir(tokenA, "ruraloeste").andExpect(status().isOk());

    JsonNode emUso = primeiroErro(definir(tokenB, "ruraloeste").andExpect(status().isConflict()));
    JsonNode reservado = primeiroErro(definir(tokenB, "www").andExpect(status().isConflict()));
    JsonNode contem = primeiroErro(
        definir(tokenB, "agroconia-suporte").andExpect(status().isConflict()));

    assertThat(emUso.get("code").asText()).isEqualTo("SLUG_INDISPONIVEL");
    JsonNode ecoado = emUso.get("rejectedValue");
    assertThat(ecoado == null || ecoado.isNull()).isTrue();
    assertThat(reservado).isEqualTo(emUso);
    assertThat(contem).isEqualTo(emUso);
  }

  @Test
  @DisplayName("troca: 30 dias entre gravacoes; o antigo fica 90 dias em carencia, depois libera")
  void trocaECarencia() throws Exception {
    definir(tokenA, "ruraloeste").andExpect(status().isOk());

    definir(tokenA, "oesterural")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ENDERECO_TROCA_RECENTE"))
        .andExpect(jsonPath("$.errors[0].expectedValue").isNotEmpty());
    // Reenviar o atual nao e troca.
    definir(tokenA, "ruraloeste").andExpect(status().isOk());

    passarIntervalo(adminA);
    definir(tokenA, "oesterural").andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("oesterural"));
    assertThat(jdbcTemplate.queryForObject("SELECT escritorio_id FROM escritorio_slug_anterior"
        + " WHERE slug = 'ruraloeste'", UUID.class)).isEqualTo(escritorioDe(adminA.usuarioId()));

    // Em carencia, ninguem mais o registra.
    definir(tokenB, "ruraloeste").andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("SLUG_INDISPONIVEL"));

    // Carencia vencida: libera.
    jdbcTemplate.update("UPDATE escritorio_slug_anterior SET liberado_em = DATEADD('DAY', -1,"
        + " CURRENT_TIMESTAMP) WHERE slug = 'ruraloeste'");
    definir(tokenB, "ruraloeste").andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio_slug_anterior"
        + " WHERE slug = 'ruraloeste'", Integer.class)).isZero();
  }

  @Test
  @DisplayName("remover: 204 mesmo dentro dos 30 dias, idempotente; o slug fica em carencia")
  void remover() throws Exception {
    definir(tokenA, "ruraloeste").andExpect(status().isOk());

    remover(tokenA).andExpect(status().isNoContent());
    remover(tokenA).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA))
        .andExpect(jsonPath("$.slug").doesNotExist())
        .andExpect(jsonPath("$.enderecoAlteravelEm").isNotEmpty());
    definir(tokenB, "ruraloeste").andExpect(status().isConflict());
    // O proprio escritorio recupera o slug depois do intervalo.
    passarIntervalo(adminA);
    definir(tokenA, "ruraloeste").andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM registro_auditoria"
        + " WHERE acao = 'ESCRITORIO_ENDERECO_ALTERADO' AND estado_antes = 'COM_ENDERECO'"
        + " AND estado_depois = 'SEM_ENDERECO'", Integer.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("so o administrador grava ou remove; produtor 403")
  void soAdministrador() throws Exception {
    jdbcTemplate.update("UPDATE contadores SET funcao_escritorio = 'CONTADOR' WHERE id = ?",
        adminA.usuarioId());
    definir(tokenA, "ruraloeste").andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("PAPEL_NAO_AUTORIZADO"));
    remover(tokenA).andExpect(status().isForbidden());
    definir(token(EMAIL_PRODUTOR), "ruraloeste").andExpect(status().isForbidden());
  }

  /** O ask do Caddy com a chave do application-test.yml. */
  private ResultActions ask(String dominio) throws Exception {
    return mockMvc.perform(get("/interno/tls/permitido").param("domain", dominio)
        .param("chave", "chave-do-ask-so-dos-testes-0123456789abcdef"));
  }

  private ResultActions loginNoSubdominio(String email, String slug) throws Exception {
    return mockMvc.perform(post("/api/auth/login").header(HttpHeaders.HOST,
            slug + ".agroconia.com.br").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\",\"escritorio\":\"%s\"}"
            .formatted(email, SENHA, slug)));
  }

  private ResultActions patchEscritorio(String token, String corpo) throws Exception {
    return mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, token)
        .contentType(MediaType.APPLICATION_JSON).content(corpo));
  }

  @Test
  @DisplayName("verificacao do CNPJ (#520): o endereco so e servido depois dela; o PATCH com outro CNPJ a derruba, sem 409 nem oraculo")
  void enderecoSoServidoComCnpjVerificado() throws Exception {
    UUID escritorioA = escritorioDe(adminA.usuarioId());
    definir(tokenA, "ruraloeste").andExpect(status().isOk());
    // Reservado (outro escritorio nao o grava), mas sem a verificacao nada e servido.
    definir(tokenB, "ruraloeste").andExpect(status().isConflict());
    ask("ruraloeste.agroconia.com.br").andExpect(status().isNotFound());
    mockMvc.perform(get("/api/publico/escritorios/ruraloeste")).andExpect(status().isNotFound());
    loginNoSubdominio(EMAIL_A, "ruraloeste").andExpect(status().isUnauthorized());

    verificacaoCnpj.verificar(escritorioA, CNPJ_A, MetodoVerificacaoCnpj.MANUAL);
    ask("ruraloeste.agroconia.com.br").andExpect(status().isOk());
    loginNoSubdominio(EMAIL_A, "ruraloeste").andExpect(status().isOk());

    // Trocar o nome (e so o nome) mantem a verificacao.
    patchEscritorio(tokenA, "{\"nome\":\"Rural Oeste Ltda\"}").andExpect(status().isOk());
    patchEscritorio(tokenA, "{\"nome\":\"Rural Oeste\",\"cnpj\":\"%s\"}".formatted(CNPJ_A))
        .andExpect(status().isOk());
    ask("ruraloeste.agroconia.com.br").andExpect(status().isOk());

    // B grava o CNPJ verificado de A: 200, como qualquer CNPJ (sem oraculo), e B nao fica verificado.
    patchEscritorio(tokenB, "{\"nome\":\"Campo Verde\",\"cnpj\":\"%s\"}".formatted(CNPJ_A))
        .andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio"
        + " WHERE cnpj_verificado_em IS NOT NULL", Integer.class)).isEqualTo(1);

    // A troca o proprio CNPJ: a verificacao cai na hora, e o endereco deixa de ser servido.
    patchEscritorio(tokenA, "{\"nome\":\"Rural Oeste\",\"cnpj\":\"%s\"}".formatted(CNPJ_B))
        .andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForList("SELECT cnpj_verificado_em FROM escritorio WHERE id = ?",
        Object.class, escritorioA)).containsExactly((Object) null);
    ask("ruraloeste.agroconia.com.br").andExpect(status().isNotFound());
    loginNoSubdominio(EMAIL_A, "ruraloeste").andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("CNPJ: sem ele, 422 ao gravar; o empresarial nao o apaga; o endereco do legado o protege")
  void exigeCnpj() throws Exception {
    jdbcTemplate.update("UPDATE escritorio SET cnpj = NULL WHERE id = ?",
        escritorioDe(adminA.usuarioId()));
    definir(tokenA, "ruraloeste")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("ENDERECO_EXIGE_CNPJ"));

    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nome\":\"Rural Oeste\",\"cnpj\":\"%s\"}".formatted(CNPJ_A)))
        .andExpect(status().isOk());
    definir(tokenA, "ruraloeste").andExpect(status().isOk());

    // Empresarial nao fica sem CNPJ (#519): a regra vem antes da do endereco.
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Rural Oeste\",\"cnpj\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_EMPRESARIAL_EXIGE_CNPJ"))
        .andExpect(jsonPath("$.errors[0].field").value("cnpj"));
    // Trocar o nome, sem mexer no CNPJ, segue igual.
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Rural Oeste Ltda\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("ruraloeste"));

    remover(tokenA).andExpect(status().isNoContent());
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Rural Oeste\",\"cnpj\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_EMPRESARIAL_EXIGE_CNPJ"));

    // Dado legado: pessoal com CNPJ e endereco (de antes do tipo). O endereco segue exigindo o CNPJ.
    jdbcTemplate.update("UPDATE escritorio SET tipo = 'PESSOAL', slug = 'legado519' WHERE id = ?",
        escritorioDe(adminA.usuarioId()));
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Rural Oeste\",\"cnpj\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].code").value("ENDERECO_EXIGE_CNPJ"));
    // Sem endereco, o pessoal apaga o CNPJ, como antes.
    remover(tokenA).andExpect(status().isNoContent());
    mockMvc.perform(patch("/api/escritorio").header(HttpHeaders.AUTHORIZATION, tokenA)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Rural Oeste\",\"cnpj\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cnpj").doesNotExist());
  }

  @Test
  @DisplayName("escritorio pessoal (#519): PUT do endereco e 409 ESCRITORIO_PESSOAL mesmo com CNPJ; DELETE segue 204")
  void pessoalSemEndereco() throws Exception {
    jdbcTemplate.update("UPDATE escritorio SET tipo = 'PESSOAL' WHERE id = ?",
        escritorioDe(adminA.usuarioId()));

    definir(tokenA, "ruraloeste")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_PESSOAL"));
    remover(tokenA).andExpect(status().isNoContent());
    assertThat(jdbcTemplate.queryForObject("SELECT slug FROM escritorio WHERE id = ?", String.class,
        escritorioDe(adminA.usuarioId()))).isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM registro_auditoria"
        + " WHERE acao = 'ESCRITORIO_ENDERECO_ALTERADO' AND sucesso = FALSE"
        + " AND codigo_erro = 'ESCRITORIO_PESSOAL'", Integer.class)).isOne();
  }

  @Test
  @DisplayName("administrador do empresarial com endereco nao aceita convite (#519): 409, escritorio e slug intactos")
  void administradorDoEmpresarialComEnderecoNaoSai() throws Exception {
    definir(tokenA, "ruraloeste").andExpect(status().isOk());
    UUID escritorioA = escritorioDe(adminA.usuarioId());
    String cnpjA = jdbcTemplate.queryForObject("SELECT cnpj FROM escritorio WHERE id = ?",
        String.class, escritorioA);
    String convite = conviteDeBPara(EMAIL_A);

    mockMvc.perform(post("/api/escritorio/membros/convites/aceitar")
            .header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\"}".formatted(convite)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("ESCRITORIO_ATUAL_EM_USO"));

    assertThat(escritorioDe(adminA.usuarioId())).isEqualTo(escritorioA);
    assertThat(cnpjA).isNotBlank();
    assertThat(jdbcTemplate.queryForObject("SELECT tipo || '|' || cnpj || '|' || slug"
        + " FROM escritorio WHERE id = ?", String.class, escritorioA))
        .isEqualTo("EMPRESARIAL|" + cnpjA + "|ruraloeste");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio_slug_anterior",
        Integer.class)).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM convite_membro"
        + " WHERE usado_em IS NULL", Integer.class)).isOne();
  }

  @Test
  @DisplayName("pessoal antigo com endereco aceita convite: o escritorio sai e o slug fica em carencia")
  void aceiteDeEquipeGuardaACarencia() throws Exception {
    definir(tokenA, "ruraloeste").andExpect(status().isOk());
    UUID escritorioA = escritorioDe(adminA.usuarioId());
    // Estado que a API nao produz (o pessoal nao grava endereco proprio, e o 0062 tornou
    // empresarial quem tinha CNPJ), mas a carencia do aceite segue valendo para ele.
    jdbcTemplate.update("UPDATE escritorio SET tipo = 'PESSOAL' WHERE id = ?", escritorioA);
    String convite = conviteDeBPara(EMAIL_A);

    mockMvc.perform(post("/api/escritorio/membros/convites/aceitar")
            .header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\"}".formatted(convite)))
        .andExpect(status().isOk());

    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio WHERE id = ?",
        Integer.class, escritorioA)).isZero();
    assertThat(jdbcTemplate.queryForList("SELECT escritorio_id FROM escritorio_slug_anterior"
        + " WHERE slug = 'ruraloeste'", UUID.class)).containsExactly((UUID) null);

    // Outro administrador (C, com CNPJ) nao toma o endereco que circula nos e-mails antigos.
    CadastroResponse adminC = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Terceiro", "11144477735", "admin@terceiro.com.br", SENHA));
    jdbcTemplate.update("UPDATE escritorio SET cnpj = '11222333000262' WHERE id = ?",
        escritorioDe(adminC.usuarioId()));
    EscritoriosDeTeste.tornarEmpresarial(jdbcTemplate, adminC.usuarioId());
    definir(token("admin@terceiro.com.br"), "ruraloeste")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("SLUG_INDISPONIVEL"));
  }

  @Test
  @DisplayName("exclusao de dados do contador unipessoal: endereco e anteriores saem sem carencia")
  void exclusaoDeDadosLiberaNaHora() throws Exception {
    definir(tokenA, "ruraloeste").andExpect(status().isOk());
    passarIntervalo(adminA);
    definir(tokenA, "oesterural").andExpect(status().isOk());

    mockMvc.perform(delete("/api/usuarios/{id}/dados", adminA.usuarioId())
            .header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
            .content("{\"senha\":\"%s\"}".formatted(SENHA)))
        .andExpect(status().isNoContent());

    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM escritorio_slug_anterior",
        Integer.class)).isZero();
    definir(tokenB, "ruraloeste").andExpect(status().isOk());
    passarIntervalo(adminB);
    definir(tokenB, "oesterural").andExpect(status().isOk());
  }
}
