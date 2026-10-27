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
 * rejeição da SEFAZ. Os padrões exigem o número isolado (sem dígito colado), então chave de
 * acesso, UUID e valor monetário não são confundidos com documento.
 *
 * <p>Diferente do {@code DataSanitizer} da Ceres, que mascara também a chave: lá o destino é o
 * provedor do modelo, onde nada fiscal precisa chegar.
 */
public final class MascaraDadosPessoais {

  private record Regra(Pattern padrao, String marcador) {
  }

  /** Só caracteres de IP literal: o parse nunca vira consulta DNS. */
  private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

  private static final List<Regra> REGRAS = List.of(
      // CNPJ antes do CPF (14 digitos contem 11).
      new Regra(Pattern.compile(
          "(?<![\\d.])\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}(?![\\d])"), "[CNPJ]"),
      new Regra(Pattern.compile(
          "(?<![\\d.])\\d{3}[.\\s]?\\d{3}[.\\s]?\\d{3}-?\\d{2}(?![\\d])"), "[CPF]"),
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
      resultado = regra.padrao().matcher(resultado).replaceAll(regra.marcador());
    }
    return resultado;
  }

  /**
   * Referência curta de um id para log (#222): {@code ref:} + 12 hex do SHA-256 do UUID.
   *
   * <p>Para o id da credencial, que é o {@code sub} do JWT: com ele no log, quem obtivesse o
   * segredo forjaria o token de uma conta específica. A referência correlaciona os eventos de uma
   * mesma conta (o hash é estável) e, com o UUID em mãos, a operação a recalcula; o caminho
   * inverso não existe.
   *
   * @param id id a referenciar
   * @return {@code ref:<12 hex>}; nulo vira {@code ref:-}
   */
  public static String referencia(UUID id) {
    if (id == null) {
      return "ref:-";
    }
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(id.toString().getBytes(StandardCharsets.UTF_8));
      return "ref:" + HexFormat.of().formatHex(hash, 0, 6);
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
