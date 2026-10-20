package br.com.agrocontabil.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Máscara de dados pessoais em texto de log (LGPD, issue #148).
 *
 * <p>Aplicada a <em>toda</em> mensagem de log pelo conversor do Logback
 * ({@code MensagemLogMascaradaConverter}, ligado em {@code logback-spring.xml}): é a rede de
 * segurança para o log que alguém escrever amanhã com um CPF dentro. O cuidado na origem continua
 * valendo (logar ids, não documentos).
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

  private static final List<Regra> REGRAS = List.of(
      // CNPJ antes do CPF (14 digitos contem 11).
      new Regra(Pattern.compile(
          "(?<![\\d.])\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}(?![\\d])"), "[CNPJ]"),
      new Regra(Pattern.compile(
          "(?<![\\d.])\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?![\\d])"), "[CPF]"),
      new Regra(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"),
      new Regra(Pattern.compile(
          "(?:\\+55\\s?)?\\(\\d{2}\\)\\s?9?\\d{4}-?\\d{4}(?![\\d])"), "[TELEFONE]"));

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
   * IP com a parte que identifica o assinante zerada: o último octeto no IPv4, tudo depois do
   * /64 no IPv6. Basta para correlacionar abuso (mesma rede) sem guardar o endereço do titular.
   *
   * @param ip endereço literal
   * @return endereço truncado; nulo volta nulo
   */
  public static String truncarIp(String ip) {
    if (ip == null) {
      return null;
    }
    if (ip.indexOf(':') >= 0) {
      String[] partes = ip.split(":", -1);
      return partes.length >= 4
          ? String.join(":", partes[0], partes[1], partes[2], partes[3]) + "::/64"
          : "[IPv6]";
    }
    int ultimoPonto = ip.lastIndexOf('.');
    return ultimoPonto > 0 ? ip.substring(0, ultimoPonto) + ".0" : "[IP]";
  }
}
