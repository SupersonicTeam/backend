package br.com.agrocontabil.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Testes do {@link UfIbge} (issue #88).
 *
 * <p>Cobre as 27 UFs, a normalização de entrada e a ausência do antigo default silencioso
 * ({@code "35"}), além da coerência entre código IBGE de município e UF.
 */
class UfIbgeTest {

  @ParameterizedTest(name = "{0} -> cUF {1}")
  @CsvSource({
      "AC,12", "AL,27", "AM,13", "AP,16", "BA,29", "CE,23", "DF,53", "ES,32", "GO,52",
      "MA,21", "MG,31", "MS,50", "MT,51", "PA,15", "PB,25", "PE,26", "PI,22", "PR,41",
      "RJ,33", "RN,24", "RO,11", "RR,14", "RS,43", "SC,42", "SE,28", "SP,35", "TO,17"
  })
  @DisplayName("resolve o cUF das 27 UFs")
  void resolveCodigoDasVinteSeteUfs(String uf, String esperado) {
    assertThat(UfIbge.codigo(uf)).contains(esperado);
  }

  @ParameterizedTest
  @CsvSource({"mt,51", "' MT ',51", "sp,35"})
  @DisplayName("normaliza caixa e espacos antes de resolver")
  void normalizaEntrada(String uf, String esperado) {
    assertThat(UfIbge.codigo(uf)).contains(esperado);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "ZZ", "XX", "BRASIL"})
  @DisplayName("UF desconhecida devolve vazio (sem default silencioso de SP)")
  void ufDesconhecidaDevolveVazio(String uf) {
    assertThat(UfIbge.codigo(uf)).isEmpty();
  }

  @Test
  @DisplayName("codigo IBGE coerente com a UF quando o prefixo bate")
  void prefixoCoerente() {
    // Cuiaba/MT = 5103403; cUF de MT = 51.
    assertThat(UfIbge.prefixoCoerente("5103403", "MT")).isTrue();
    assertThat(UfIbge.prefixoCoerente("3550308", "SP")).isTrue();
  }

  @Test
  @DisplayName("codigo IBGE de outra UF e incoerente")
  void prefixoIncoerente() {
    // Sao Paulo capital (3550308) declarado como MT.
    assertThat(UfIbge.prefixoCoerente("3550308", "MT")).isFalse();
  }

  @Test
  @DisplayName("entradas invalidas sao incoerentes (nunca lancam)")
  void entradasInvalidasSaoIncoerentes() {
    assertThat(UfIbge.prefixoCoerente(null, "MT")).isFalse();
    assertThat(UfIbge.prefixoCoerente("5", "MT")).isFalse();
    assertThat(UfIbge.prefixoCoerente("5103403", null)).isFalse();
    assertThat(UfIbge.prefixoCoerente("5103403", "ZZ")).isFalse();
  }
}
