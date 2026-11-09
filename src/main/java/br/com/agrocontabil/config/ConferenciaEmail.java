package br.com.agrocontabil.config;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * Conferências da configuração de e-mail feitas na subida (issue #460), comuns aos três e-mails
 * (redefinição de senha e os dois convites). Configuração errada impede a subida em vez de virar
 * falha silenciosa em cada envio.
 */
final class ConferenciaEmail {

  /** Profiles em que um link {@code http} é aceito: a máquina do desenvolvedor e os testes. */
  static final List<String> PERFIS_LOCAIS = List.of("dev", "test");

  private static final String PROPRIEDADE_HOST = "spring.mail.host";
  private static final String PROPRIEDADE_AUTH = "spring.mail.properties.mail.smtp.auth";

  private ConferenciaEmail() {
  }

  /**
   * Cliente SMTP do modo {@code SMTP}, conferido.
   *
   * <p>O host é conferido pelo texto, não pela existência do cliente: o compose entrega
   * {@code SPRING_MAIL_HOST=""}, e com a propriedade presente, mesmo vazia, o Boot cria o cliente,
   * que tentaria {@code localhost:25} em todo envio. Com {@code mail.smtp.auth} ligado, usuário e
   * senha vazios fazem o provedor recusar todo envio, então também impedem a subida.
   *
   * @param ambiente    propriedades {@code spring.mail.*}
   * @param mailSender  cliente SMTP do Boot, se configurado
   * @param remetente   endereço {@code From}
   * @param dominio     domínio verificado no provedor
   * @return o cliente SMTP
   */
  static JavaMailSender clienteSmtp(Environment ambiente, ObjectProvider<JavaMailSender> mailSender,
      String remetente, String dominio) {
    if (!StringUtils.hasText(ambiente.getProperty(PROPRIEDADE_HOST))) {
      throw new IllegalStateException("app.seguranca.redefinicao.modo-email=SMTP exige "
          + "spring.mail.host preenchido (SPRING_MAIL_HOST).");
    }
    if (ambiente.getProperty(PROPRIEDADE_AUTH, Boolean.class, false)
        && (!StringUtils.hasText(ambiente.getProperty("spring.mail.username"))
            || !StringUtils.hasText(ambiente.getProperty("spring.mail.password")))) {
      throw new IllegalStateException("app.seguranca.redefinicao.modo-email=SMTP com "
          + "autenticacao exige spring.mail.username e spring.mail.password preenchidos "
          + "(SPRING_MAIL_USERNAME e SPRING_MAIL_PASSWORD).");
    }
    exigirRemetenteNoDominio(remetente, dominio);
    JavaMailSender cliente = mailSender.getIfAvailable();
    if (cliente == null) {
      throw new IllegalStateException("app.seguranca.redefinicao.modo-email=SMTP sem o cliente "
          + "SMTP do Spring Boot: confira spring.mail.host (SPRING_MAIL_HOST).");
    }
    return cliente;
  }

  /**
   * O remetente é lido como endereço de e-mail ({@code Nome <endereco>} ou só o endereço), e o
   * domínio do endereço tem de ser exatamente o verificado. Comparar o fim do texto deixaria passar
   * {@code AgroConIA <x@agroconia.local>}, que termina em {@code >}.
   *
   * @param remetente valor de {@code EMAIL_REMETENTE}
   * @param dominio   domínio verificado no provedor
   */
  static void exigirRemetenteNoDominio(String remetente, String dominio) {
    InternetAddress endereco;
    try {
      endereco = new InternetAddress(remetente == null ? "" : remetente.strip(), true);
    } catch (AddressException e) {
      throw new IllegalStateException("app.seguranca.redefinicao.remetente (EMAIL_REMETENTE) nao "
          + "e um endereco de e-mail valido. Exemplo: AgroConIA <nao-responda@" + dominio + ">.");
    }
    String texto = endereco.getAddress();
    int arroba = texto == null ? -1 : texto.lastIndexOf('@');
    String dominioDoRemetente =
        arroba < 0 ? "" : texto.substring(arroba + 1).toLowerCase(Locale.ROOT);
    if (endereco.isGroup() || !dominioDoRemetente.equals(dominio.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException("app.seguranca.redefinicao.remetente (EMAIL_REMETENTE) "
          + "precisa ser do dominio verificado no provedor (app.email.dominio-verificado = "
          + dominio + "): de outro dominio o e-mail sai sem DKIM alinhado.");
    }
  }

  /**
   * Link de e-mail em {@code https}. Vale em {@code prod} sempre e, fora de {@code dev}/{@code test},
   * quando o e-mail é enviado de fato (modo {@code SMTP}): o link leva token ou leva ao login. Com o
   * envio desligado o link não sai, e a regra não impede a subida de uma VPS que ainda não tem as
   * URLs no compose.
   *
   * @param propriedade nome da propriedade, para a mensagem
   * @param url         valor configurado
   * @param envia       se o modo de e-mail é {@code SMTP}
   * @param ambiente    profiles ativos
   */
  static void exigirHttps(String propriedade, String url, boolean envia, Environment ambiente) {
    List<String> perfis = Arrays.asList(ambiente.getActiveProfiles());
    boolean local = perfis.stream().anyMatch(PERFIS_LOCAIS::contains);
    boolean exige = perfis.contains("prod") || (envia && !local);
    if (exige && (url == null || !url.startsWith("https://"))) {
      throw new IllegalStateException(propriedade + " precisa ser HTTPS fora de dev/test com o "
          + "envio de e-mail ligado (e sempre em prod): o link vai no e-mail.");
    }
  }
}
