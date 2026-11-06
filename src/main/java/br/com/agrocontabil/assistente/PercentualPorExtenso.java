package br.com.agrocontabil.assistente;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Percentuais escritos por extenso ("zero vírgula dois por cento", "vinte e cinco por cento") em
 * valor numérico (issue #269).
 *
 * <p>Parte da lei escreve o percentual só por extenso (Lei 9.528, art. 6º: contribuição ao SENAR
 * "de zero vírgula dois por cento"). O {@link ValidadorNumeros} só via dígitos, e a resposta certa
 * "0,2%" caía como número sem fonte. Reconhece de zero a novecentos e noventa e nove, com parte
 * decimal ("vírgula" seguida de palavras: "zero cinco" = 05, "vinte e cinco" = 25). Função pura.
 */
final class PercentualPorExtenso {

  private static final Map<String, Integer> PALAVRAS = Map.ofEntries(
      Map.entry("zero", 0), Map.entry("um", 1), Map.entry("uma", 1), Map.entry("dois", 2),
      Map.entry("duas", 2), Map.entry("tres", 3), Map.entry("quatro", 4), Map.entry("cinco", 5),
      Map.entry("seis", 6), Map.entry("sete", 7), Map.entry("oito", 8), Map.entry("nove", 9),
      Map.entry("dez", 10), Map.entry("onze", 11), Map.entry("doze", 12), Map.entry("treze", 13),
      Map.entry("quatorze", 14), Map.entry("catorze", 14), Map.entry("quinze", 15),
      Map.entry("dezesseis", 16), Map.entry("dezessete", 17), Map.entry("dezoito", 18),
      Map.entry("dezenove", 19), Map.entry("vinte", 20), Map.entry("trinta", 30),
      Map.entry("quarenta", 40), Map.entry("cinquenta", 50), Map.entry("sessenta", 60),
      Map.entry("setenta", 70), Map.entry("oitenta", 80), Map.entry("noventa", 90),
      Map.entry("cem", 100), Map.entry("cento", 100), Map.entry("duzentos", 200),
      Map.entry("trezentos", 300), Map.entry("quatrocentos", 400), Map.entry("quinhentos", 500),
      Map.entry("seiscentos", 600), Map.entry("setecentos", 700), Map.entry("oitocentos", 800),
      Map.entry("novecentos", 900));

  private static final Pattern MARCAS = Pattern.compile("\\p{M}+");

  private static final String PALAVRA = "(?:" + PALAVRAS.keySet().stream()
      .sorted(Comparator.comparingInt(String::length).reversed())
      .collect(Collectors.joining("|")) + ")";
  private static final String SEQUENCIA = PALAVRA + "(?:\\s+(?:e\\s+)?" + PALAVRA + ")*";
  private static final Pattern PERCENTUAL = Pattern.compile(
      "\\b(" + SEQUENCIA + ")(?:\\s+virgula\\s+(" + SEQUENCIA + "))?\\s+por\\s+cento\\b");

  private PercentualPorExtenso() {}

  /**
   * Os percentuais por extenso do texto, na ordem em que aparecem, já normalizados.
   *
   * @param texto texto livre (com ou sem acento, qualquer caixa); nulo dá lista vazia
   * @return valores (ex.: {@code "zero vírgula dois por cento"} → {@code 0.2})
   */
  static List<BigDecimal> valores(String texto) {
    if (texto == null || texto.isBlank()) {
      return List.of();
    }
    String simples = MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD))
        .replaceAll("").toLowerCase(Locale.ROOT);
    List<BigDecimal> valores = new ArrayList<>();
    Matcher m = PERCENTUAL.matcher(simples);
    while (m.find()) {
      String decimal = m.group(2) == null ? "" : decimais(m.group(2));
      valores.add(new BigDecimal(inteiro(m.group(1)) + (decimal.isEmpty() ? "" : "." + decimal))
          .stripTrailingZeros());
    }
    return valores;
  }

  /** Soma as palavras de "vinte e cinco" (25). */
  private static int inteiro(String palavras) {
    int total = 0;
    for (String palavra : palavras.split("\\s+")) {
      if (!palavra.equals("e")) {
        total += PALAVRAS.get(palavra);
      }
    }
    return total;
  }

  /** Parte depois da vírgula: cada "zero" do começo é um dígito 0, e o resto é um número. */
  private static String decimais(String palavras) {
    StringBuilder digitos = new StringBuilder();
    String resto = palavras;
    while (resto.startsWith("zero")) {
      digitos.append('0');
      resto = resto.substring(4).strip();
    }
    if (!resto.isEmpty()) {
      digitos.append(inteiro(resto));
    }
    return digitos.toString();
  }
}
