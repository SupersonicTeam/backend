package br.com.agrocontabil.common;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máscara de dados pessoais em texto de log (LGPD, issue #148).
 *
 * <p>Aplicada a <em>toda</em> mensagem de log e a todo stack trace pelos conversores do Logback
 * ({@code MensagemLogMascaradaConverter} e {@code ExcecaoLogMascaradaConverter}, ligados em
 * {@code logback-spring.xml}): é a rede de segurança para o log que alguém escrever amanhã com um
 * CPF dentro, e para o detalhe do banco que uma violação de constraint carrega na exceção. O
 * cuidado na origem continua valendo (logar ids, não documentos).
 *
 * <p>Mascara CPF, CNPJ, e-mail e telefone. A chave de acesso da NF-e (44 dígitos) fica de fora de
 * propósito — não é dado pessoal e é o que a operação precisa ler no log para investigar uma
 * rejeição da SEFAZ. CPF e CNPJ exigem o número isolado: sem letra ou dígito colado (nem ponto
 * antes), então chave de acesso, UUID, hash, {@code jti} e valor monetário não são confundidos
 * com documento. Até o PR #310 só dígito e ponto contavam, e um token hex com 11 dígitos
 * seguidos de letra (último grupo de UUID, por exemplo) saía {@code [CPF]a}. Limite aceito: CPF
 * grudado em letra, sem separador ({@code cpf52998224725}), não é mascarado; qualquer outro
 * separador ({@code _ : = ( " -} espaço) basta.
 *
 * <p><strong>CNPJ alfanumérico da RFB (issue #313):</strong> 12 posições com dígito ou letra
 * maiúscula e 2 dígitos verificadores ({@code 12.ABC.345/01DE-35}), com e sem pontuação, viram
 * {@code [CNPJ]} só quando os dígitos verificadores conferem
 * ({@link DocumentoFiscalValidator#isCnpjAlfanumericoValido}). Sem essa conferência, todo token
 * de 14 caracteres terminado em 2 dígitos (hash, base64) sairia {@code [CNPJ]}, o mesmo tipo de
 * falso positivo que o PR #310 corrigiu no CPF. Só maiúsculas, que é o formato da RFB e do XML: o
 * log carrega hash em hex minúsculo, e com minúscula cerca de um em cada cem desses tokens viraria
 * {@code [CNPJ]}. O {@code DataSanitizer} da Ceres ganha a sua regra, em qualquer caixa, depois
 * da rodada do benchmark.
 *
 * <p>Diferente do {@code DataSanitizer} da Ceres, que mascara também a chave: lá o destino é o
 * provedor do modelo, onde nada fiscal precisa chegar.
 */
public final class MascaraDadosPessoais {

  /**
   * Uma regra: o padrão, o marcador e a conferência do trecho achado (o que não confere fica
   * como está).
   */
  private record Regra(Pattern padrao, String marcador, Predicate<String> confere) {

    Regra(Pattern padrao, String marcador) {
      this(padrao, marcador, trecho -> true);
    }
  }

  /** Pontuação do CNPJ, tirada antes de conferir o DV do alfanumérico. */
  private static final Pattern PONTUACAO_CNPJ = Pattern.compile("[./-]");

  /** Só caracteres de IP literal: o parse nunca vira consulta DNS. */
  private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

  private static final List<Regra> REGRAS = List.of(
      // CNPJ antes do CPF (14 digitos contem 11).
      new Regra(Pattern.compile(
          "(?<![A-Za-z0-9.])\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}(?![A-Za-z0-9])"),
          "[CNPJ]"),
      // CNPJ alfanumerico (#313): so com o DV conferido e so com maiusculas (o log carrega hash em
      // hex minusculo). Depois do numerico, que ja trocou os so de digitos, e antes do CPF.
      new Regra(Pattern.compile("(?<![A-Za-z0-9.])[0-9A-Z]{2}\\.?[0-9A-Z]{3}\\.?[0-9A-Z]{3}"
          + "/?[0-9A-Z]{4}-?\\d{2}(?![A-Za-z0-9])"), "[CNPJ]",
          trecho -> DocumentoFiscalValidator.isCnpjAlfanumericoValido(
              PONTUACAO_CNPJ.matcher(trecho).replaceAll(""))),
      new Regra(Pattern.compile(
          "(?<![A-Za-z0-9.])\\d{3}[.\\s]?\\d{3}[.\\s]?\\d{3}-?\\d{2}(?![A-Za-z0-9])"),
          "[CPF]"),
      new Regra(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"),
      // DDD entre parenteses, ou sem eles mas com o hifen do numero (sem o hifen, uma sequencia
      // de digitos qualquer — valor, id numerico — viraria telefone).
      new Regra(Pattern.compile(
          "(?:\\+55\\s?)?(?:\\(\\d{2}\\)\\s?9?\\d{4}-?\\d{4}"
              + "|(?<![\\d.])\\d{2}\\s9?\\d{4}-\\d{4})(?![\\d])"), "[TELEFONE]"));

  private MascaraDadosPessoais() {
  }

  /**
   * Troca CPF, CNPJ, e-mail e telefone por marcadores.
   *
   * @param texto mensagem de log
   * @return a mensagem mascarada; nula ou vazia volta como veio
   */
  public static String mascarar(String texto) {
    if (texto == null || texto.isEmpty()) {
      return texto;
    }
    String resultado = texto;
    for (Regra regra : REGRAS) {
      // O trecho que nao confere volta como veio, protegido: um "$" ou uma "\" nele seriam lidos
      // como referencia de grupo pela troca.
      resultado = regra.padrao().matcher(resultado).replaceAll(achado ->
          regra.confere().test(achado.group())
              ? regra.marcador()
              : Matcher.quoteReplacement(achado.group()));
    }
    return resultado;
  }

  /**
   * Referência curta de um id para log (#222): {@code ref:} + 12 hex do SHA-256 do UUID, em duas
   * metades de 6 separadas por hífen ({@code ref:6bbd4e-b564e9}).
   *
   * <p>Para o id da credencial, que é o {@code sub} do JWT: com ele no log, quem obtivesse o
   * segredo forjaria o token de uma conta específica. A referência correlaciona os eventos de uma
   * mesma conta (o hash é estável) e, com o UUID em mãos, a operação a recalcula; o caminho
   * inverso não existe.
   *
   * <p>O hífen deixa a referência imune à {@link #mascarar} por construção, sem depender da
   * fronteira das regras: no formato antigo (12 hex corridos), 11 dígitos seguidos de letra viravam
   * {@code ref:[CPF]a}. Com no máximo 6 dígitos de cada lado do hífen nenhuma regra casa: CPF e
   * CNPJ exigem 9 e 12 dígitos antes do hífen, e o telefone exige parênteses no DDD ou espaço
   * depois dele. Para buscar uma conta em log anterior ao PR #310, tire o hífen
   * ({@code 466962-69333f} → {@code 46696269333f}).
   *
   * @param id id a referenciar
   * @return {@code ref:<6 hex>-<6 hex>}; nulo vira {@code ref:-}
   */
  public static String referencia(UUID id) {
    if (id == null) {
      return "ref:-";
    }
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(id.toString().getBytes(StandardCharsets.UTF_8));
      HexFormat hex = HexFormat.of();
      return "ref:" + hex.formatHex(hash, 0, 3) + "-" + hex.formatHex(hash, 3, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponivel na JVM", e);
    }
  }

  /**
   * IP com a parte que identifica o assinante zerada: o último octeto no IPv4, tudo depois do
   * /64 no IPv6. Basta para correlacionar abuso (mesma rede) sem guardar o endereço do titular.
   *
   * <p>Trunca os bytes do endereço, não o texto: a forma comprimida ({@code 2804:14c::5}) e o IPv4
   * mapeado em IPv6 ({@code ::ffff:203.0.113.7}, comum atrás de proxy dual-stack) sairiam inteiros
   * se o corte fosse por {@code ':'}.
   *
   * @param ip endereço literal
   * @return endereço truncado; nulo volta nulo; o que não for IP literal vira {@code [IP]}
   */
  public static String truncarIp(String ip) {
    if (ip == null) {
      return null;
    }
    if (!IP_LITERAL.matcher(ip).matches()) {
      return "[IP]";
    }
    InetAddress endereco;
    try {
      endereco = InetAddress.getByName(ip);
    } catch (UnknownHostException e) {
      return "[IP]";
    }
    byte[] bytes = endereco.getAddress();
    if (endereco instanceof Inet4Address) {
      bytes[3] = 0;
      return toTexto(bytes);
    }
    Arrays.fill(bytes, 8, 16, (byte) 0);
    return toTexto(bytes) + "/64";
  }

  private static String toTexto(byte[] bytes) {
    try {
      return InetAddress.getByAddress(bytes).getHostAddress().replaceFirst(":0:0:0:0$", "::");
    } catch (UnknownHostException e) {
      return "[IP]";
    }
  }
}
