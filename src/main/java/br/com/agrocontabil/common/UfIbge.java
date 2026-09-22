package br.com.agrocontabil.common;

import java.util.Map;
import java.util.Optional;

/**
 * Códigos IBGE de UF (cUF) e coerência entre código de município e UF (issue #88).
 *
 * <p>Utilitário técnico (inglês por convenção do projeto), extraído do
 * {@code NotaFiscalNfeMapper} para ser reutilizado pelo {@code FiscalRulesValidator} e pela
 * validação Bean Validation do endereço do destinatário.
 *
 * <p>O código IBGE de um município tem 7 dígitos e começa pelo <strong>cUF</strong> de 2 dígitos da
 * sua UF (ex.: Cuiabá/MT = {@code 5103403}, e {@code 51} é o cUF de MT). É por isso que a coerência
 * entre {@code codigoMunicipioIbge} e {@code uf} pode ser verificada sem a tabela completa dos
 * 5.570 municípios (débito registrado para a Sprint 7).
 *
 * <p>Diferentemente do mapa que substitui, {@link #codigo(String)} devolve {@link Optional} em vez
 * de cair num default silencioso ({@code "35"}, São Paulo): uma UF desconhecida passava a emitir
 * uma chave de acesso com cUF errado sem nenhum sinal.
 */
public final class UfIbge {

  /**
   * UF convencional de destinatário no exterior (exportação), conforme o layout da NF-e. Não tem
   * cUF: o código de município do exterior ({@code 9999999}) não pertence a UF brasileira, então
   * a coerência de prefixo não se aplica a ela.
   */
  public static final String UF_EXTERIOR = "EX";

  /** Verifica se a UF informada é a do exterior (exportação). */
  public static boolean isExterior(String uf) {
    return uf != null && UF_EXTERIOR.equalsIgnoreCase(uf.trim());
  }

  /** Tabela UF → cUF. Fonte: Manual de Orientação do Contribuinte (MOC) NF-e 4.00, Anexo IV. */
  private static final Map<String, String> CODIGO_POR_UF =
      Map.ofEntries(
          Map.entry("AC", "12"),
          Map.entry("AL", "27"),
          Map.entry("AM", "13"),
          Map.entry("AP", "16"),
          Map.entry("BA", "29"),
          Map.entry("CE", "23"),
          Map.entry("DF", "53"),
          Map.entry("ES", "32"),
          Map.entry("GO", "52"),
          Map.entry("MA", "21"),
          Map.entry("MG", "31"),
          Map.entry("MS", "50"),
          Map.entry("MT", "51"),
          Map.entry("PA", "15"),
          Map.entry("PB", "25"),
          Map.entry("PE", "26"),
          Map.entry("PI", "22"),
          Map.entry("PR", "41"),
          Map.entry("RJ", "33"),
          Map.entry("RN", "24"),
          Map.entry("RO", "11"),
          Map.entry("RR", "14"),
          Map.entry("RS", "43"),
          Map.entry("SC", "42"),
          Map.entry("SE", "28"),
          Map.entry("SP", "35"),
          Map.entry("TO", "17"));

  private UfIbge() {
  }

  /**
   * Código IBGE (cUF, 2 dígitos) da UF informada.
   *
   * @param uf sigla da UF (aceita minúsculas e espaços; pode ser nula)
   * @return o cUF, ou vazio quando a UF é nula, em branco ou não é uma das 27 UFs
   */
  public static Optional<String> codigo(String uf) {
    if (uf == null || uf.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(CODIGO_POR_UF.get(uf.trim().toUpperCase()));
  }

  /**
   * Verifica se um código IBGE de município pertence à UF informada, comparando os 2 primeiros
   * dígitos do código com o cUF da UF.
   *
   * <p>Retorna {@code false} quando qualquer um dos dois é nulo/inválido — o chamador decide se
   * a ausência é erro (emissão) ou tolerada (rascunho).
   *
   * @param codigoMunicipioIbge código IBGE do município (7 dígitos)
   * @param uf sigla da UF
   * @return {@code true} apenas quando ambos são válidos e o prefixo corresponde
   */
  public static boolean prefixoCoerente(String codigoMunicipioIbge, String uf) {
    if (codigoMunicipioIbge == null || codigoMunicipioIbge.length() < 2) {
      return false;
    }
    return codigo(uf)
        .map(cuf -> cuf.equals(codigoMunicipioIbge.substring(0, 2)))
        .orElse(false);
  }
}
