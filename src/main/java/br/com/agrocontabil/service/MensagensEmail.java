package br.com.agrocontabil.service;

import br.com.agrocontabil.service.ModeloEmail.Bloco;
import br.com.agrocontabil.service.ModeloEmail.Botao;
import br.com.agrocontabil.service.ModeloEmail.Destaque;
import br.com.agrocontabil.service.ModeloEmail.Email;
import br.com.agrocontabil.service.ModeloEmail.Nota;
import br.com.agrocontabil.service.ModeloEmail.Paragrafo;
import br.com.agrocontabil.service.ModeloEmail.Secao;
import br.com.agrocontabil.service.ModeloEmail.Trecho;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Assunto, pré-cabeçalho, texto e HTML dos três e-mails do sistema (issue #460): redefinição de
 * senha (#209), convite da carteira (#177) e convite de equipe (#228), todos no {@link ModeloEmail}.
 *
 * <p>O assunto nunca leva o nome do escritório: o nome é livre e o cadastro de contador é público,
 * e um assunto com texto do atacante sairia do domínio do AgroConIA. No corpo, o nome chega já
 * limpo pelo {@link ConviteCarteiraService#nomeParaEmail} e é escapado pelo modelo. Nenhum dado
 * pessoal do destinatário entra no e-mail.
 */
public final class MensagensEmail {

  /**
   * Um e-mail pronto para o envio.
   *
   * @param assunto      assunto
   * @param preCabecalho texto da prévia na lista de e-mails (vai escondido no HTML)
   * @param texto        parte {@code text/plain}
   * @param html         parte {@code text/html}
   */
  public record Mensagem(String assunto, String preCabecalho, String texto, String html) {
  }

  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH'h'mm");

  private MensagensEmail() {
  }

  /**
   * E-mail do link de redefinição de senha.
   *
   * @param link     link com o token no fragmento
   * @param validade vida do link
   * @param contato  e-mail de dúvidas do rodapé
   * @return o e-mail
   */
  public static Mensagem redefinicaoSenha(String link, Duration validade, String contato) {
    String prazo = prazo(validade);
    return montar(new Email(
        "Link para criar uma nova senha no AgroConIA",
        "O link vale por " + prazo + " e só uma vez. Se não foi você, ignore este e-mail.",
        "Criar uma nova senha",
        List.of(
            new Paragrafo(List.of(Trecho.de(
                "Recebemos um pedido para criar uma nova senha para a sua conta no AgroConIA."))),
            new Botao("Criar nova senha", link),
            new Destaque(List.of(Trecho.forte("O link vale por " + prazo + " e só uma vez."))),
            new Paragrafo(List.of(Trecho.de(
                "Se não foi você que pediu, ignore este e-mail: a sua senha não muda.")))),
        ModeloEmail.origem(link), contato));
  }

  /**
   * E-mail do convite do escritório ao produtor. Sem token: aceitar exige entrar com o e-mail
   * convidado, na página Escritório. Escritório verificado: o CNPJ vai ao lado do nome; não
   * verificado: o aviso para confirmar o convite com o contador antes de aceitar (#520).
   *
   * @param escritorio nome do escritório, já limpo, e o CNPJ só se verificado
   * @param link       página onde o produtor responde os convites ({@code /escritorio})
   * @param expiraEm   fim da validade do convite
   * @param contato    e-mail de dúvidas do rodapé
   * @return o e-mail
   */
  public static Mensagem conviteCarteira(EscritorioDoConvite escritorio, String link,
      OffsetDateTime expiraEm, String contato) {
    return montar(new Email(
        "Convite de escritório de contabilidade no AgroConIA",
        quemNoPreCabecalho(escritorio.nome()) + " quer atender você pelo AgroConIA. O convite vale até " + data(expiraEm) + ".",
        "Você recebeu um convite de escritório",
        semNulos(
            new Paragrafo(concatenar(quemConvidou(escritorio),
                Trecho.de(" para a carteira de clientes dele no AgroConIA."))),
            avisoNaoVerificado(escritorio, "Antes de aceitar, confirme o convite com o seu "
                + "contador por um canal que você já conhece, como o telefone do escritório."),
            new Secao("O que muda se você aceitar", List.of(
                List.of(Trecho.de("O escritório passa a ver as suas notas fiscais, a apuração e "
                    + "os créditos de IBS e CBS, os seus vencimentos e os documentos que você "
                    + "enviar a ele.")),
                List.of(Trecho.de("Ele pode aprovar ou devolver as notas que você mandar para "
                    + "revisão, registrar guias de imposto e pedir documentos.")),
                List.of(Trecho.de("Ele não edita as suas notas e não vê a sua senha.")),
                List.of(Trecho.de("Você pode encerrar o vínculo quando quiser, na página "),
                    Trecho.forte("Escritório"), Trecho.de(".")))),
            new Secao("Como aceitar", List.of(
                List.of(Trecho.de("Já tem conta no AgroConIA com este e-mail? Clique em "),
                    Trecho.forte("Ver convite"), Trecho.de(", entre e aceite.")),
                List.of(Trecho.de("Ainda não tem conta? Crie a sua com este mesmo e-mail. "
                    + "Depois de entrar, abra "), Trecho.forte("Escritório"),
                    Trecho.de(" no menu: o convite estará lá.")))),
            new Botao("Ver convite", link),
            new Destaque(List.of(Trecho.forte("O convite vale até " + dataHora(expiraEm)
                + " (horário de Brasília)."))),
            new Nota(List.of(Trecho.de("Não conhece o escritório? Recuse o convite na página "
                + "Escritório ou ignore este e-mail. Sem o seu aceite, ele não vê nada.")))),
        ModeloEmail.origem(link), contato));
  }

  /**
   * E-mail do convite de equipe do escritório, com o selo ou o aviso do convite da carteira (#520).
   *
   * @param escritorio nome do escritório, já limpo, e o CNPJ só se verificado
   * @param link       link com o token no fragmento ({@code /equipe/convite#token=})
   * @param expiraEm   fim da validade do convite
   * @param contato    e-mail de dúvidas do rodapé
   * @return o e-mail
   */
  public static Mensagem conviteEquipe(EscritorioDoConvite escritorio, String link,
      OffsetDateTime expiraEm, String contato) {
    return montar(new Email(
        "Convite para a equipe de um escritório no AgroConIA",
        quemNoPreCabecalho(escritorio.nome()) + " convidou você para a equipe. O convite vale até " + data(expiraEm) + ".",
        "Você recebeu um convite para uma equipe",
        semNulos(
            new Paragrafo(concatenar(quemConvidou(escritorio),
                Trecho.de(" para a equipe dele no AgroConIA."))),
            avisoNaoVerificado(escritorio, "Antes de entrar, confirme o convite com o escritório "
                + "por um canal que você já conhece."),
            new Secao("O que muda se você aceitar", List.of(
                List.of(Trecho.de("Você passa a atender os clientes do escritório com a sua "
                    + "própria conta: notas, apuração, vencimentos e pedidos de documento.")),
                List.of(Trecho.de("O que você pode fazer depende da função que o escritório der "
                    + "a você.")),
                List.of(Trecho.de("Se a sua conta já é de outro escritório com clientes ou "
                    + "equipe, é preciso encerrar esses vínculos antes de entrar.")))),
            new Secao("Como aceitar", List.of(
                List.of(Trecho.de("Clique em "), Trecho.forte("Entrar na equipe"),
                    Trecho.de(".")),
                List.of(Trecho.de("Já tem conta de contador com este e-mail? Entre com ela e "
                    + "confirme.")),
                List.of(Trecho.de("Ainda não tem conta? Crie a sua na própria página, com o seu "
                    + "nome, CPF ou CNPJ e uma senha. O e-mail é este, o do convite.")))),
            new Botao("Entrar na equipe", link),
            new Destaque(List.of(Trecho.forte("O convite vale até " + dataHora(expiraEm)
                + " (horário de Brasília) e só uma vez."))),
            new Nota(List.of(Trecho.de("Não conhece o escritório? Ignore este e-mail.")))),
        ModeloEmail.origem(link), contato));
  }

  private static Mensagem montar(Email email) {
    return new Mensagem(email.assunto(), email.preCabecalho(), ModeloEmail.texto(email),
        ModeloEmail.html(email));
  }

  /** {@code 24/11/2026}, em Brasília. */
  static String data(OffsetDateTime instante) {
    return DATA.format(instante.atZoneSameInstant(BRASILIA));
  }

  /** {@code 24/11/2026, às 15h30}, em Brasília. */
  static String dataHora(OffsetDateTime instante) {
    return data(instante) + ", às " + HORA.format(instante.atZoneSameInstant(BRASILIA));
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

  /**
   * "O escritório **Nome** convidou você", ou sem o nome quando ele ficou vazio na limpeza. Com o
   * CNPJ verificado, o selo vai ao lado do nome (#520): o nome é texto livre, e é o CNPJ que diz
   * quem convidou.
   */
  private static List<Trecho> quemConvidou(EscritorioDoConvite escritorio) {
    List<Trecho> quem = new ArrayList<>(vazio(escritorio.nome())
        ? List.of(Trecho.de("Um escritório de contabilidade"))
        : List.of(Trecho.de("O escritório "), Trecho.forte(escritorio.nome())));
    if (escritorio.verificado()) {
      quem.add(Trecho.de(" (CNPJ " + cnpjFormatado(escritorio.cnpjVerificado())
          + ", verificado pelo AgroConIA)"));
    }
    quem.add(Trecho.de(" convidou você"));
    return List.copyOf(quem);
  }

  /**
   * Aviso do convite de escritório com o CNPJ não verificado (#520); nulo quando verificado (o
   * {@link #semNulos} o tira da lista).
   */
  private static Bloco avisoNaoVerificado(EscritorioDoConvite escritorio, String confirme) {
    if (escritorio.verificado()) {
      return null;
    }
    return new Destaque(List.of(Trecho.forte("Escritório ainda não verificado."),
        Trecho.de(" O AgroConIA ainda não conferiu o CNPJ deste escritório, e o nome foi "
            + "informado por ele mesmo. " + confirme)));
  }

  /** Os blocos do e-mail, sem os que não se aplicam (nulos). */
  private static List<Bloco> semNulos(Bloco... blocos) {
    return Arrays.stream(blocos).filter(Objects::nonNull).toList();
  }

  /** {@code 11.444.777/0001-61}; fora dos 14 caracteres, como veio. */
  static String cnpjFormatado(String cnpj) {
    if (cnpj == null || cnpj.length() != 14) {
      return cnpj;
    }
    return cnpj.substring(0, 2) + "." + cnpj.substring(2, 5) + "." + cnpj.substring(5, 8) + "/"
        + cnpj.substring(8, 12) + "-" + cnpj.substring(12);
  }

  private static String quemNoPreCabecalho(String escritorio) {
    return vazio(escritorio) ? "Um escritório de contabilidade" : escritorio;
  }

  private static List<Trecho> concatenar(List<Trecho> inicio, Trecho fim) {
    return Stream.concat(inicio.stream(), Stream.of(fim)).toList();
  }

  private static boolean vazio(String texto) {
    return texto == null || texto.isBlank();
  }
}
