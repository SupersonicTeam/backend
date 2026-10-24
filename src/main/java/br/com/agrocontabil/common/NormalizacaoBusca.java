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

  private NormalizacaoBusca() {
  }

  /**
   * Normaliza um texto: sem acento, em minúsculas e com espaços colapsados.
   *
   * @param texto texto livre (pode ser {@code null})
   * @return texto normalizado; vazio para {@code null}
   */
  public static String normalizar(String texto) {
    if (texto == null) {
      return "";
    }
    String semAcento = DIACRITICOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD))
        .replaceAll("");
    return ESPACOS.matcher(semAcento.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }

  /**
   * Junta e normaliza as partes, ignorando as nulas.
   *
   * @param partes partes do texto pesquisável
   * @return partes normalizadas separadas por espaço
   */
  public static String juntar(Object... partes) {
    return normalizar(Stream.of(partes).filter(Objects::nonNull).map(String::valueOf)
        .collect(Collectors.joining(" ")));
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
