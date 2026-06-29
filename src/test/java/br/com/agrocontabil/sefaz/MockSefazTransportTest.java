package br.com.agrocontabil.sefaz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do {@link MockSefazTransport}.
 *
 * <p>Verifica o retorno determinístico de autorização e a derivação do protocol a partir
 * da key de acesso. Sem contexto Spring — instanciacao direta.
 */
class MockSefazTransportTest {

  private static final String CHAVE = "41262612345678000195550010000000011000000012";

  private final MockSefazTransport transport = new MockSefazTransport();

  @Test
  @DisplayName("enviar deve retornar AUTORIZADA com cStat 100 e protocolo derivado da chave")
  void enviarShouldReturnAuthorizedWithDerivedProtocol() {
    SefazReturn retorno = transport.send("<xml/>", CHAVE, SefazEnvironment.HOMOLOGACAO);

    assertThat(retorno.situation()).isEqualTo(SefazSituation.AUTORIZADA);
    assertThat(retorno.cStat()).isEqualTo("100");
    assertThat(retorno.xMotivo()).isEqualTo("Autorizado o uso da NF-e");
    assertThat(retorno.protocol()).isEqualTo("1" + CHAVE.substring(0, 14));
    assertThat(retorno.authorizationDate()).isNotNull();
  }

  @Test
  @DisplayName("consultarStatus deve retornar AUTORIZADA de forma deterministica")
  void consultarStatusShouldReturnAuthorized() {
    SefazReturn retorno = transport.queryStatus(CHAVE, SefazEnvironment.HOMOLOGACAO);

    assertThat(retorno.situation()).isEqualTo(SefazSituation.AUTORIZADA);
    assertThat(retorno.cStat()).isEqualTo("100");
  }

  @Test
  @DisplayName("enviar deve usar protocolo de fallback quando a chave e nula")
  void enviarShouldFallbackProtocolWhenChaveIsNull() {
    SefazReturn retorno = transport.send("<xml/>", null, SefazEnvironment.HOMOLOGACAO);

    assertThat(retorno.situation()).isEqualTo(SefazSituation.AUTORIZADA);
    assertThat(retorno.protocol()).isEqualTo("100000000000001");
  }
}
