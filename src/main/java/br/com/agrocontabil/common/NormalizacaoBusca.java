package br.com.agrocontabil.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Normalização do texto de busca (issue #174): minúsculas, sem acento, espaços colapsados.
 *
 * <p>A mesma regra vale para o que é gravado (a coluna {@code nota_fiscal.busca_normalizada}) e
 * para o termo que o cliente digita, então "AGRÍCOLA" casa com "agricola". Roda em Java nos dois
 * lados, e não no banco, para dar o mesmo resultado no PostgreSQL e no H2 dos testes.
 */
public final class NormalizacaoBusca {

  /** Marcas diacríticas que sobram da decomposição NFD ("á" → "a" + acento agudo). */
  private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}+");

  private static final Pattern ESPACOS = Pattern.compile("\\s+");

  /** Tamanho da coluna {@code nota_fiscal.busca_normalizada} (changeSet 0037). */
  public static final int TAMANHO_MAXIMO = 600;

  private NormalizacaoBusca() {
  }

  /**
   * Normaliza um texto: sem acento, em minúsculas e com espaços colapsados.
   *
   * <p>Depois de tirar as marcas, recompõe em NFC: escritas como o coreano se decompõem no NFD em
   * letras que não são marcas (uma sílaba vira dois ou três jamo), e sem recompor o texto crescia
   * até três vezes (issue #174, achado do review).
   *
   * @param texto texto livre (pode ser {@code null})
   * @return texto normalizado; vazio para {@code null}
   */
  public static String normalizar(String texto) {
    if (texto == null) {
      return "";
    }
    String semAcento = Normalizer.normalize(
        DIACRITICOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll(""),
        Normalizer.Form.NFC);
    return ESPACOS.matcher(semAcento.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }

  /**
   * Junta e normaliza as partes, ignorando as nulas, cortado no tamanho da coluna.
   *
   * @param partes partes do texto pesquisável
   * @return partes normalizadas separadas por espaço, até {@link #TAMANHO_MAXIMO} caracteres
   */
  public static String juntar(Object... partes) {
    String junto = normalizar(Stream.of(partes).filter(Objects::nonNull).map(String::valueOf)
        .collect(Collectors.joining(" ")));
    return junto.length() > TAMANHO_MAXIMO ? junto.substring(0, TAMANHO_MAXIMO) : junto;
  }

  /**
   * Escapa os curingas do {@code LIKE} ({@code %}, {@code _}) e o próprio caractere de escape
   * (a barra invertida): o que o cliente digita é texto, nunca padrão.
   *
   * @param termo termo já normalizado
   * @return termo seguro para {@code LIKE} com a barra invertida como escape
   */
  public static String escaparLike(String termo) {
    return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
