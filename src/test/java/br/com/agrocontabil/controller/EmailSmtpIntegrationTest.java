package br.com.agrocontabil.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.agrocontabil.dto.CadastroRequest;
import br.com.agrocontabil.dto.CadastroResponse;
import br.com.agrocontabil.security.PapelUsuario;
import br.com.agrocontabil.service.CadastroService;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Os três e-mails pelo SMTP de verdade (issue #460), contra um servidor falso (GreenMail): o que
 * o titular recebe no modo {@code SMTP}, da requisição HTTP até a caixa de entrada.
 *
 * <p>O GreenMail sobe no carregamento da classe, antes do contexto, para a porta dinâmica entrar
 * nas propriedades. Os envios rodam em segundo plano, num pool de uma thread por fluxo: para
 * provar que um e-mail <em>não</em> saiu, o teste espera um e-mail posterior do mesmo pool.
 */
@SpringBootTest(properties = {
    "app.seguranca.redefinicao.modo-email=SMTP",
    "app.seguranca.redefinicao.remetente=AgroConIA <nao-responda@agroconia.com.br>",
    "app.seguranca.redefinicao.url-frontend=https://www.agroconia.com.br/redefinir-senha",
    "app.carteira.convite.url-frontend=https://www.agroconia.com.br/escritorio",
    "app.escritorio.convite.url-frontend=https://www.agroconia.com.br/equipe/convite",
    "app.email.links-redefinicao-por-dia=2",
    "spring.mail.host=127.0.0.1",
    "spring.mail.username=" + EmailSmtpIntegrationTest.USUARIO,
    "spring.mail.password=" + EmailSmtpIntegrationTest.SENHA_SMTP,
    // O GreenMail fala SMTP sem TLS.
    "spring.mail.properties.mail.smtp.starttls.enable=false",
    "spring.mail.properties.mail.smtp.starttls.required=false"})
@AutoConfigureMockMvc
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("E-mail pelo SMTP: os tres fluxos ponta a ponta (#460)")
class EmailSmtpIntegrationTest {

  static final String USUARIO = "usuario-smtp";
  static final String SENHA_SMTP = "senha-smtp-de-teste";

  private static final String SENHA = "cavalo bateria grampo correto";
  private static final String EMAIL_PRODUTOR = "produtor@fazenda.com.br";
  private static final String EMAIL_CONTADOR = "contador@escritorio.com.br";
  private static final long ESPERA_MS = 10_000;

  static final GreenMail SMTP = iniciarSmtp();

  private static GreenMail iniciarSmtp() {
    GreenMail servidor = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    servidor.setUser("nao-responda@agroconia.com.br", USUARIO, SENHA_SMTP);
    servidor.start();
    return servidor;
  }

  @DynamicPropertySource
  static void portaSmtp(DynamicPropertyRegistry registro) {
    registro.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
  }

  @AfterAll
  static void pararSmtp() {
    SMTP.stop();
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CadastroService cadastroService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private JavaMailSender mailSender;

  private CadastroResponse contador;

  @BeforeEach
  void setUp() throws Exception {
    cleaner.limparTudo();
    SMTP.purgeEmailFromAllMailboxes();
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Joaquim Conceição", "52998224725", EMAIL_PRODUTOR, SENHA));
    contador = cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.CONTADOR, "Maria Contadora", "11222333000181", EMAIL_CONTADOR, SENHA));
  }

  @Test
  @DisplayName("redefinicao: acento em UTF-8, From com nome, Reply-To e o token so no fragmento")
  void redefinicaoDeSenha() throws Exception {
    esqueci(EMAIL_PRODUTOR);

    MimeMessage mensagem = unicaRecebida();
    assertThat(mensagem.getAllRecipients()).extracting(Object::toString)
        .containsExactly(EMAIL_PRODUTOR);
    InternetAddress remetente = (InternetAddress) mensagem.getFrom()[0];
    assertThat(remetente.getPersonal()).isEqualTo("AgroConIA");
    assertThat(remetente.getAddress()).isEqualTo("nao-responda@agroconia.com.br");
    assertThat(mensagem.getReplyTo()).extracting(Object::toString)
        .containsExactly("contato@gwnext.com.br");
    assertThat(mensagem.getSubject()).isEqualTo("Redefinição de senha do AgroConIA");
    assertThat(mensagem.getContentType()).containsIgnoringCase("charset=UTF-8");
    String texto = texto(mensagem);
    assertThat(texto)
        .contains("em até 30 minutos")
        .contains("Se você não pediu")
        .containsPattern("https://www\\.agroconia\\.com\\.br/redefinir-senha#token=[\\w-]{40,}")
        .doesNotContain("?token");
  }

  @Test
  @DisplayName("convite da carteira: nome do escritorio sem URL e o link de /escritorio")
  void conviteDaCarteira() throws Exception {
    jdbcTemplate.update("UPDATE escritorio SET nome = ? WHERE id = (SELECT escritorio_id FROM"
        + " contadores WHERE id = ?)", "Contabilidade São João www.golpe.com.br",
        contador.usuarioId());
    mockMvc.perform(post("/api/contador/convites")
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_CONTADOR))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"convidado@sitio.com.br\"}"))
        .andExpect(status().isAccepted());

    MimeMessage mensagem = unicaRecebida();
    assertThat(mensagem.getSubject())
        .isEqualTo("Convite de escritório de contabilidade no AgroConIA");
    String texto = texto(mensagem);
    assertThat(texto)
        .contains("O escritório de contabilidade \"Contabilidade São João\" convidou você")
        .contains("\nhttps://www.agroconia.com.br/escritorio\n")
        .contains("em até 14 dias")
        .doesNotContain("golpe");
  }

  @Test
  @DisplayName("convite de equipe: link de /equipe/convite com o token no fragmento")
  void conviteDeEquipe() throws Exception {
    mockMvc.perform(post("/api/escritorio/membros/convites")
            .header(HttpHeaders.AUTHORIZATION, token(EMAIL_CONTADOR))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"novo.membro@escritorio.com.br\",\"funcao\":\"CONTADOR\"}"))
        .andExpect(status().isAccepted());

    MimeMessage mensagem = unicaRecebida();
    assertThat(mensagem.getSubject())
        .isEqualTo("Convite para a equipe de um escritório no AgroConIA");
    assertThat(texto(mensagem))
        .contains("equipe do escritório \"Maria Contadora\"")
        .containsPattern("https://www\\.agroconia\\.com\\.br/equipe/convite#token=[\\w-]{40,}")
        .contains("em até 7 dias");
  }

  @Test
  @DisplayName("conta @example.com (o seed) nao recebe; a de dominio real recebe")
  void destinoReservadoNaoRecebe() throws Exception {
    cadastroService.cadastrar(new CadastroRequest(
        PapelUsuario.PRODUTOR, "Seed", "11144477735", "seed.demo@example.com", SENHA));
    esqueci("seed.demo@example.com");
    esqueci(EMAIL_PRODUTOR);

    // O mesmo pool processa os dois pedidos em ordem: chegou o segundo, o primeiro ja passou.
    MimeMessage mensagem = unicaRecebida();
    assertThat(mensagem.getAllRecipients()).extracting(Object::toString)
        .containsExactly(EMAIL_PRODUTOR);
  }

  @Test
  @DisplayName("limite por conta: o 3o link seguido nao sai (resposta 202) e o 2o continua valendo")
  void limiteDeLinksPorConta() throws Exception {
    for (int pedido = 1; pedido <= 2; pedido++) {
      assertThat(esqueci(EMAIL_PRODUTOR)).isEqualTo(202);
      assertThat(SMTP.waitForIncomingEmail(ESPERA_MS, pedido)).isTrue();
      envelhecerPedidos();
    }
    assertThat(esqueci(EMAIL_PRODUTOR)).isEqualTo(202);
    String linkAntes = jdbcTemplate.queryForObject("SELECT token_hash FROM redefinicao_senha",
        String.class);

    // Prova de que o 3o pedido ja foi processado: o pedido seguinte, de outra conta, chega.
    esqueci(EMAIL_CONTADOR);
    assertThat(SMTP.waitForIncomingEmail(ESPERA_MS, 3)).isTrue();
    assertThat(SMTP.getReceivedMessages()).hasSize(3);
    assertThat(SMTP.getReceivedMessages()[2].getAllRecipients()[0].toString())
        .isEqualTo(EMAIL_CONTADOR);
    assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM redefinicao_senha r JOIN"
        + " credencial_usuario c ON c.id = r.credencial_id WHERE c.papel = 'PRODUTOR'",
        String.class)).isEqualTo(linkAntes);
  }

  @Test
  @DisplayName("os timeouts e a autenticacao do application.yml chegam ao cliente SMTP")
  void timeoutsDoYmlNoCliente() {
    Properties propriedades = ((JavaMailSenderImpl) mailSender).getJavaMailProperties();
    assertThat(propriedades)
        .containsEntry("mail.smtp.connectiontimeout", "5000")
        .containsEntry("mail.smtp.timeout", "10000")
        .containsEntry("mail.smtp.writetimeout", "10000")
        .containsEntry("mail.smtp.auth", "true")
        .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
  }

  private int esqueci(String email) throws Exception {
    return mockMvc.perform(post("/api/auth/senha/esqueci").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\"}".formatted(email))).andReturn().getResponse().getStatus();
  }

  /** Corpo decodificado, com as quebras de linha do SMTP ({@code \r\n}) como {@code \n}. */
  private static String texto(MimeMessage mensagem) throws Exception {
    return ((String) mensagem.getContent()).replace("\r\n", "\n");
  }

  private MimeMessage unicaRecebida() {
    assertThat(SMTP.waitForIncomingEmail(ESPERA_MS, 1)).isTrue();
    MimeMessage[] recebidas = SMTP.getReceivedMessages();
    assertThat(recebidas).hasSize(1);
    return recebidas[0];
  }

  private void envelhecerPedidos() {
    jdbcTemplate.update("UPDATE redefinicao_senha SET criado_em = ?",
        OffsetDateTime.now().minus(Duration.ofMinutes(2)));
  }

  private String token(String email) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, SENHA))).andReturn();
    return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
        .get("accessToken").asText();
  }
}
