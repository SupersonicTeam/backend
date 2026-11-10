package br.com.agrocontabil.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * Modelo único dos e-mails do AgroConIA (issue #460): o mesmo conteúdo vira a parte HTML e a parte
 * texto do {@code multipart/alternative}, então as duas nunca divergem.
 *
 * <p>O HTML segue o que os clientes de e-mail aceitam: tabelas, CSS inline, 600 px, sem script,
 * sem CSS externo e sem fonte web. O cabeçalho é uma faixa verde-escura com o nome em texto: com a
 * imagem bloqueada, o nome segura o cabeçalho, e o modo escuro do Gmail quase não mexe numa faixa
 * escura. O {@code @media (prefers-color-scheme: dark)} vale no Apple Mail e no Outlook do Mac; os
 * outros invertem as cores por conta própria, e as cores foram escolhidas para continuar legíveis.
 *
 * <p>Todo texto dinâmico (o nome do escritório, o link) é escapado aqui. O link é absoluto e leva
 * o token no fragmento ({@code #token=}); o e-mail não tem pixel nem link de rastreamento.
 */
final class ModeloEmail {

  /** Um trecho de texto, em negrito ou não. */
  record Trecho(String texto, boolean negrito) {

    static Trecho de(String texto) {
      return new Trecho(texto, false);
    }

    static Trecho forte(String texto) {
      return new Trecho(texto, true);
    }
  }

  /** Bloco do corpo, na ordem em que aparece. */
  sealed interface Bloco permits Paragrafo, Secao, Botao, Destaque, Nota {
  }

  /** Parágrafo comum. */
  record Paragrafo(List<Trecho> trechos) implements Bloco {
  }

  /** Subtítulo com uma lista de itens. */
  record Secao(String titulo, List<List<Trecho>> itens) implements Bloco {
  }

  /** Botão de ação, com o link escrito por extenso logo abaixo. */
  record Botao(String texto, String link) implements Bloco {
  }

  /** Caixa de destaque (o prazo). */
  record Destaque(List<Trecho> trechos) implements Bloco {
  }

  /** Texto menor, de apoio. */
  record Nota(List<Trecho> trechos) implements Bloco {
  }

  /**
   * Um e-mail montado.
   *
   * @param assunto      assunto
   * @param preCabecalho texto que o cliente mostra ao lado do assunto, na lista de e-mails
   * @param titulo       título do corpo
   * @param blocos       corpo, na ordem
   * @param urlSite      origem do site ({@code https://www.agroconia.com.br}), para o logo e a
   *                     política de dados
   * @param contato      e-mail de dúvidas do rodapé
   */
  record Email(String assunto, String preCabecalho, String titulo, List<Bloco> blocos,
      String urlSite, String contato) {
  }

  static final String EMPRESA = "AgroConIA, da GWNext Technologies LTDA";
  static final String SEGURANCA =
      "O AgroConIA nunca pede a sua senha por e-mail, telefone ou mensagem.";

  /** Verde-escuro do logo: a faixa do cabeçalho. */
  private static final String VERDE_ESCURO = "#1b4630";
  /** Verde primário do frontend ({@code --mat-sys-primary}): o botão. */
  private static final String VERDE = "#2e7648";
  /** Verde-claro do frontend ({@code --mat-sys-primary-container}): o "IA" e a caixa de prazo. */
  private static final String VERDE_CLARO = "#b8e6c4";
  private static final String FUNDO = "#f2f5f1";
  private static final String TEXTO = "#1f2a22";
  private static final String APOIO = "#55665a";
  private static final String FONTE = "Arial, Helvetica, sans-serif";

  private ModeloEmail() {
  }

  /**
   * Origem de um link absoluto, sem caminho ({@code https://www.agroconia.com.br}).
   *
   * @param link link absoluto do e-mail
   * @return esquema, host e porta
   */
  static String origem(String link) {
    // Pelo texto, sem URI.create: um caractere inválido no caminho não pode derrubar o e-mail.
    int esquema = link.indexOf("://");
    int inicio = esquema < 0 ? 0 : esquema + 3;
    int fim = inicio;
    while (fim < link.length() && "/?#".indexOf(link.charAt(fim)) < 0) {
      fim++;
    }
    return link.substring(0, fim);
  }

  /**
   * Versão texto puro, para o cliente que não mostra HTML e para quem lê o código-fonte.
   *
   * @param email conteúdo
   * @return texto
   */
  static String texto(Email email) {
    StringBuilder texto = new StringBuilder("AgroConIA\n\n").append(email.titulo()).append("\n\n");
    for (Bloco bloco : email.blocos()) {
      switch (bloco) {
        case Paragrafo p -> texto.append(plano(p.trechos())).append("\n\n");
        case Secao s -> {
          texto.append(s.titulo()).append('\n');
          s.itens().forEach(item -> texto.append("- ").append(plano(item)).append('\n'));
          texto.append('\n');
        }
        case Botao b -> texto.append(b.texto()).append(":\n").append(b.link()).append("\n\n");
        case Destaque d -> texto.append(plano(d.trechos())).append("\n\n");
        case Nota n -> texto.append(plano(n.trechos())).append("\n\n");
      }
    }
    return texto.append(SEGURANCA).append("\n\n")
        .append("--\n")
        .append("Este é um e-mail automático.")
        .append(vazio(email.contato()) ? "" : " Dúvidas: " + endereco(email.contato()))
        .append('\n')
        .append("Política de dados: ").append(email.urlSite()).append("/politica-dados\n")
        .append(EMPRESA).append('\n')
        .toString();
  }

  /**
   * Versão HTML.
   *
   * @param email conteúdo
   * @return documento HTML completo
   */
  static String html(Email email) {
    String site = esc(email.urlSite());
    StringBuilder corpo = new StringBuilder();
    corpo.append("<h1 class=\"texto\" style=\"margin:0 0 20px 0;font-family:").append(FONTE)
        .append(";font-size:22px;line-height:30px;font-weight:bold;color:").append(TEXTO)
        .append(";\">").append(esc(email.titulo())).append("</h1>\n");
    for (Bloco bloco : email.blocos()) {
      switch (bloco) {
        case Paragrafo p -> corpo.append(paragrafo(p.trechos(), "16px", "24px", TEXTO, "texto"));
        case Secao s -> corpo.append(secao(s));
        case Botao b -> corpo.append(botao(b));
        case Destaque d -> corpo.append(destaque(d.trechos()));
        case Nota n -> corpo.append(paragrafo(n.trechos(), "14px", "21px", APOIO, "apoio"));
      }
    }
    corpo.append(paragrafo(List.of(Trecho.de(SEGURANCA)), "14px", "21px", APOIO, "apoio"));

    return """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="light dark">
        <meta name="supported-color-schemes" content="light dark">
        <title>%1$s</title>
        <style>
          @media (prefers-color-scheme: dark) {
            .fundo { background-color: #0f1712 !important; }
            .cartao { background-color: #17231b !important; }
            .texto { color: #e4ece6 !important; }
            .apoio { color: #a9b9ae !important; }
            .destaque { background-color: #20382a !important; color: #e4ece6 !important; }
            .link { color: #9ed8b2 !important; }
          }
          @media only screen and (max-width: 620px) {
            .conteudo { padding: 24px 20px !important; }
          }
        </style>
        </head>
        <body class="fundo" style="margin:0;padding:0;background-color:%2$s;">
        <div style="display:none;max-height:0;overflow:hidden;mso-hide:all;font-size:1px;\
        line-height:1px;color:%2$s;opacity:0;">%3$s%4$s</div>
        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" \
        class="fundo" style="background-color:%2$s;">
        <tr><td align="center" style="padding:24px 12px;">
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" \
        style="width:100%%;max-width:600px;">
        <tr><td style="background-color:%5$s;border-radius:12px 12px 0 0;padding:18px 32px;">
        <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
        <td style="vertical-align:middle;padding-right:12px;"><img src="%6$s/apple-touch-icon.png" \
        width="40" height="40" alt="" style="display:block;border:0;border-radius:8px;"></td>
        <td style="vertical-align:middle;font-family:%7$s;font-size:22px;line-height:28px;\
        font-weight:bold;color:#ffffff;">AgroCon<span style="color:%8$s;">IA</span></td>
        </tr></table>
        </td></tr>
        <tr><td class="cartao conteudo" style="background-color:#ffffff;padding:32px;\
        border-radius:0 0 12px 12px;">
        %9$s</td></tr>
        <tr><td style="padding:20px 32px;font-family:%7$s;font-size:12px;line-height:18px;\
        color:%10$s;" class="apoio">
        Este é um e-mail automático.%11$s<br>
        <a href="%6$s/politica-dados" class="link" style="color:%12$s;">Política de dados</a><br>
        %13$s
        </td></tr>
        </table>
        </td></tr>
        </table>
        </body>
        </html>
        """.formatted(esc(email.assunto()), FUNDO, esc(email.preCabecalho()), ESPACADOR,
        VERDE_ESCURO, site, FONTE, VERDE_CLARO, corpo, APOIO, duvidas(email.contato()), VERDE,
        esc(EMPRESA));
  }

  /**
   * Depois do pré-cabeçalho, o Gmail e o Apple Mail emendam o começo do corpo na prévia da
   * lista; caracteres invisíveis enchem esse espaço.
   */
  private static final String ESPACADOR = "&#8199;&#65279;&#847;".repeat(60);

  private static String paragrafo(List<Trecho> trechos, String tamanho, String altura,
      String cor, String classe) {
    return "<p class=\"" + classe + "\" style=\"margin:0 0 16px 0;font-family:" + FONTE
        + ";font-size:" + tamanho + ";line-height:" + altura + ";color:" + cor + ";\">"
        + html(trechos) + "</p>\n";
  }

  private static String secao(Secao secao) {
    StringBuilder html = new StringBuilder("<p class=\"texto\" style=\"margin:8px 0 8px 0;"
        + "font-family:" + FONTE + ";font-size:16px;line-height:24px;font-weight:bold;color:"
        + TEXTO + ";\">" + esc(secao.titulo()) + "</p>\n");
    html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
        + "style=\"margin:0 0 16px 0;\">\n");
    for (List<Trecho> item : secao.itens()) {
      html.append("<tr><td class=\"texto\" style=\"vertical-align:top;padding:0 10px 8px 0;"
          + "font-family:" + FONTE + ";font-size:16px;line-height:24px;color:" + VERDE
          + ";\">&#8226;</td><td class=\"texto\" style=\"padding:0 0 8px 0;font-family:" + FONTE
          + ";font-size:16px;line-height:24px;color:" + TEXTO + ";\">" + html(item)
          + "</td></tr>\n");
    }
    return html.append("</table>\n").toString();
  }

  /** Botão de tabela (funciona no Outlook) e o link por extenso, para quem não consegue clicar. */
  private static String botao(Botao botao) {
    String link = esc(botao.link());
    return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
        + "style=\"margin:8px 0 12px 0;\"><tr><td align=\"center\" bgcolor=\"" + VERDE
        + "\" style=\"border-radius:8px;background-color:" + VERDE + ";\">"
        + "<a href=\"" + link + "\" target=\"_blank\" style=\"display:inline-block;"
        + "padding:14px 28px;font-family:" + FONTE + ";font-size:16px;line-height:20px;"
        + "font-weight:bold;color:#ffffff;text-decoration:none;border-radius:8px;\">"
        + esc(botao.texto()) + "</a></td></tr></table>\n"
        + "<p class=\"apoio\" style=\"margin:0 0 20px 0;font-family:" + FONTE + ";font-size:13px;"
        + "line-height:19px;color:" + APOIO + ";\">Se o botão não abrir, copie este endereço no "
        + "navegador:<br><a href=\"" + link + "\" class=\"link\" style=\"color:" + VERDE
        + ";word-break:break-all;\">" + link + "</a></p>\n";
  }

  private static String destaque(List<Trecho> trechos) {
    return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
        + "border=\"0\" style=\"margin:0 0 20px 0;\"><tr><td class=\"destaque\" "
        + "style=\"background-color:#eaf6ee;border-left:4px solid " + VERDE + ";"
        + "border-radius:6px;padding:12px 16px;font-family:" + FONTE + ";font-size:15px;"
        + "line-height:22px;color:" + TEXTO + ";\">" + html(trechos) + "</td></tr></table>\n";
  }

  private static String html(List<Trecho> trechos) {
    StringBuilder html = new StringBuilder();
    for (Trecho trecho : trechos) {
      html.append(trecho.negrito() ? "<strong>" + esc(trecho.texto()) + "</strong>"
          : esc(trecho.texto()));
    }
    return html.toString();
  }

  private static String plano(List<Trecho> trechos) {
    StringBuilder texto = new StringBuilder();
    trechos.forEach(trecho -> texto.append(trecho.texto()));
    return texto.toString();
  }

  /** "Dúvidas: contato" com o link {@code mailto:}, ou nada sem contato configurado. */
  private static String duvidas(String contato) {
    if (vazio(contato)) {
      return "";
    }
    String endereco = esc(endereco(contato));
    return " Dúvidas: <a href=\"mailto:" + endereco + "\" class=\"link\" style=\"color:" + VERDE
        + ";\">" + endereco + "</a>";
  }

  /**
   * Só o endereço do contato: o {@code app.email.reply-to} aceita nome de exibição
   * ({@code GWNext <contato@gwnext.com.br>}), que quebraria o {@code mailto:} do rodapé.
   */
  static String endereco(String contato) {
    try {
      return new InternetAddress(contato.strip()).getAddress();
    } catch (AddressException e) {
      return contato.strip();
    }
  }

  private static boolean vazio(String texto) {
    return texto == null || texto.isBlank();
  }

  private static String esc(String texto) {
    return HtmlUtils.htmlEscape(texto == null ? "" : texto, "UTF-8");
  }
}
