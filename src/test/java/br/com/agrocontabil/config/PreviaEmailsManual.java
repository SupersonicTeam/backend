package br.com.agrocontabil.config;

import br.com.agrocontabil.service.MensagensEmail;
import br.com.agrocontabil.service.MensagensEmail.Mensagem;
import br.com.agrocontabil.service.TetoEnvioEmail;
import jakarta.mail.internet.MimeMessage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Prévias dos três e-mails (issue #460), com dados de exemplo e o mesmo código do envio: para cada
 * um, o HTML, o texto puro e o {@code .eml} completo ({@code multipart/alternative}), que abre no
 * Outlook, no Apple Mail ou no Thunderbird.
 *
 * <p>Ferramenta manual: não roda no {@code ./mvnw test}. Uso:
 * {@code ./mvnw test -Dtest=PreviaEmailsManual -Dprevia.saida=<pasta>}.
 */
@EnabledIfSystemProperty(named = "previa.saida", matches = ".+")
class PreviaEmailsManual {

  private static final String CONTATO = "contato@gwnext.com.br";
  private static final String SITE = "https://www.agroconia.com.br";
  private static final String ESCRITORIO = "Contabilidade Rural Oeste";
  /** Token de exemplo, no formato dos reais (256 bits em Base64url). */
  private static final String TOKEN = "q3Vt7xKpL0mZ9sYbR2nC8wE4hJ6fA1dG5uT0iO3pQ7k";

  @Test
  void gerarPrevias() throws Exception {
    Path pasta = Path.of(System.getProperty("previa.saida"));
    Files.createDirectories(pasta);
    OffsetDateTime agora = OffsetDateTime.parse("2026-11-10T15:30:00-03:00");

    Map<String, Mensagem> emails = new LinkedHashMap<>();
    emails.put("1-convite-carteira", MensagensEmail.conviteCarteira(ESCRITORIO,
        SITE + "/escritorio", agora.plus(Duration.ofDays(14)), CONTATO));
    emails.put("2-convite-equipe", MensagensEmail.conviteEquipe(ESCRITORIO,
        SITE + "/equipe/convite#token=" + TOKEN, agora.plus(Duration.ofDays(7)), CONTATO));
    emails.put("3-redefinicao-senha", MensagensEmail.redefinicaoSenha(
        SITE + "/redefinir-senha#token=" + TOKEN, Duration.ofMinutes(30), CONTATO));

    EmailProperties propriedades = new EmailProperties("agroconia.com.br", CONTATO, 0, 80, 5,
        List.of());
    StaticListableBeanFactory fabrica = new StaticListableBeanFactory();
    fabrica.addBean("mailSender", new JavaMailSenderImpl());
    EnvioEmailSmtp envio = new EnvioEmailSmtp(fabrica.getBeanProvider(JavaMailSender.class),
        "AgroConIA <nao-responda@agroconia.com.br>", propriedades,
        new TetoEnvioEmail(propriedades, Clock.systemUTC()));

    StringBuilder indice = new StringBuilder("Prévias dos e-mails do AgroConIA (#460)\n\n");
    for (Map.Entry<String, Mensagem> email : emails.entrySet()) {
      String nome = email.getKey();
      Mensagem mensagem = email.getValue();
      Files.writeString(pasta.resolve(nome + ".html"), mensagem.html(), StandardCharsets.UTF_8);
      Files.writeString(pasta.resolve(nome + ".txt"), mensagem.texto(), StandardCharsets.UTF_8);
      MimeMessage mime = new JavaMailSenderImpl().createMimeMessage();
      envio.preparo("produtor.exemplo@gmail.com", mensagem).prepare(mime);
      mime.setSentDate(Date.from(agora.toInstant()));
      mime.saveChanges();
      try (OutputStream saida = Files.newOutputStream(pasta.resolve(nome + ".eml"))) {
        mime.writeTo(saida);
      }
      indice.append(nome).append('\n')
          .append("  Assunto: ").append(mensagem.assunto()).append('\n')
          .append("  Pré-cabeçalho: ").append(mensagem.preCabecalho()).append("\n\n");
    }
    Files.writeString(pasta.resolve("0-assuntos.txt"), indice, StandardCharsets.UTF_8);
  }
}
