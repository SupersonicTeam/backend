package br.com.agrocontabil.service;

import java.time.Duration;

/**
 * Assunto e texto dos três e-mails do sistema (issue #460): redefinição de senha (#209), convite
 * da carteira (#177) e convite de equipe (#228). Texto puro, com acento (o envio é em UTF-8), marca
 * AgroConIA e nada além do nome do escritório, do link e da marca.
 *
 * <p>O assunto nunca leva o nome do escritório: o nome é livre e o cadastro de contador é público,
 * e um assunto com texto do atacante sairia do domínio do AgroConIA. No corpo, o nome chega já
 * limpo pelo {@link ConviteCarteiraService#nomeParaEmail}.
 */
public final class MensagensEmail {

  /** Assunto e texto de um e-mail. */
  public record Mensagem(String assunto, String texto) {
  }

  private static final String RODAPE = """

      AgroConIA
      Este e-mail é automático. Se precisar de ajuda, responda a esta mensagem.
      """;

  private MensagensEmail() {
  }

  /**
   * E-mail do link de redefinição de senha.
   *
   * @param link     link com o token no fragmento
   * @param validade vida do link
   * @return assunto e texto
   */
  public static Mensagem redefinicaoSenha(String link, Duration validade) {
    return new Mensagem("Redefinição de senha do AgroConIA", """
        Olá,

        Recebemos um pedido para redefinir a senha da sua conta no AgroConIA.

        Para escolher uma senha nova, abra o link abaixo em até %s:

        %s

        O link vale uma vez só. Se você não pediu a redefinição, ignore este e-mail: sua senha \
        continua a mesma.
        """.formatted(prazo(validade), link) + RODAPE);
  }

  /**
   * E-mail do convite do escritório ao produtor. Sem token: aceitar exige entrar com o e-mail
   * convidado.
   *
   * @param escritorio nome do escritório, já limpo
   * @param link       página onde o produtor responde os convites
   * @param validade   vida do convite
   * @return assunto e texto
   */
  public static Mensagem conviteCarteira(String escritorio, String link, Duration validade) {
    String quem = vazio(escritorio) ? "Um escritório de contabilidade"
        : "O escritório de contabilidade \"" + escritorio + "\"";
    return new Mensagem("Convite de escritório de contabilidade no AgroConIA", """
        Olá,

        %s convidou você para a carteira de clientes dele no AgroConIA.

        Para responder, entre no AgroConIA com este e-mail (ou crie sua conta com ele) e abra a \
        página do escritório em até %s:

        %s

        O escritório só passa a ver seus dados depois que você aceitar. Se não conhece o \
        escritório, recuse o convite ou ignore este e-mail.
        """.formatted(quem, prazo(validade), link) + RODAPE);
  }

  /**
   * E-mail do convite de equipe do escritório.
   *
   * @param escritorio nome do escritório, já limpo
   * @param link       link com o token no fragmento
   * @param validade   vida do convite
   * @return assunto e texto
   */
  public static Mensagem conviteEquipe(String escritorio, String link, Duration validade) {
    String equipe = vazio(escritorio) ? "de um escritório de contabilidade"
        : "do escritório \"" + escritorio + "\"";
    return new Mensagem("Convite para a equipe de um escritório no AgroConIA", """
        Olá,

        Você recebeu um convite para a equipe %s no AgroConIA.

        Para aceitar, abra o link abaixo em até %s e entre (ou crie sua conta) com este e-mail:

        %s

        O link vale uma vez só. Se não conhece o escritório, ignore este e-mail.
        """.formatted(equipe, prazo(validade), link) + RODAPE);
  }

  /**
   * Prazo por extenso: dias inteiros, senão horas, senão minutos ({@code 1 dia}, {@code 12 horas},
   * {@code 30 minutos}). Nunca arredonda para cima: o texto não promete mais tempo do que o link
   * vale.
   */
  static String prazo(Duration validade) {
    long minutos = Math.max(1, validade.toMinutes());
    if (minutos >= 1440 && minutos % 1440 == 0) {
      return plural(minutos / 1440, "dia", "dias");
    }
    if (minutos >= 60) {
      return plural(minutos / 60, "hora", "horas");
    }
    return plural(minutos, "minuto", "minutos");
  }

  private static String plural(long quantidade, String singular, String plural) {
    return quantidade + " " + (quantidade == 1 ? singular : plural);
  }

  private static boolean vazio(String texto) {
    return texto == null || texto.isBlank();
  }
}
