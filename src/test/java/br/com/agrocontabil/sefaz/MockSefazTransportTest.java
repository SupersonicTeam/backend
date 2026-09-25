package br.com.agrocontabil.sefaz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do {@link MockSefazTransport}.
 *
 * <p>Verifica o retorno determinístico de autorização e a derivação do protocolo a partir
 * da chave de acesso. Sem contexto Spring — instanciacao direta.
 */
class MockSefazTransportTest {

  private static final String CHAVE = "41262612345678000195550010000000011000000012";

  private final MockSefazTransport transport = new MockSefazTransport();

  @Test
  @DisplayName("enviar deve retornar AUTORIZADA com cStat 100 e protocolo derivado da chave")
  void enviarShouldReturnAuthorizedWithDerivedProtocol() {
    SefazRetorno retorno = transport.enviar("<xml/>", CHAVE, SefazAmbiente.HOMOLOGACAO);

    assertThat(retorno.situacao()).isEqualTo(SituacaoSefaz.AUTORIZADA);
    assertThat(retorno.cStat()).isEqualTo("100");
    assertThat(retorno.xMotivo()).isEqualTo("Autorizado o uso da NF-e");
    assertThat(retorno.protocolo()).isEqualTo("1" + CHAVE.substring(0, 14));
    assertThat(retorno.dataAutorizacao()).isNotNull();
  }

  @Test
  @DisplayName("consultarStatus retorna AUTORIZADA para chave ja transmitida")
  void consultarStatusShouldReturnAuthorizedForTransmittedChave() {
    transport.enviar("<xml/>", CHAVE, SefazAmbiente.HOMOLOGACAO);

    SefazRetorno retorno = transport.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO);

    assertThat(retorno.situacao()).isEqualTo(SituacaoSefaz.AUTORIZADA);
    assertThat(retorno.cStat()).isEqualTo("100");
  }

  @Test
  @DisplayName("consultarStatus recusa chave nunca transmitida com cStat 217")
  void consultarStatusShouldRejectUnknownChave() {
    // issue #96: o mock respondia AUTORIZADA para QUALQUER chave. Combinado com a compensacao
    // que deixa a nota em PENDENTE_SEFAZ quando o envio falha, isso fazia o job "autorizar" uma
    // NF-e que nunca saiu e publicar NotaEmitidaEvent — debito de IBS/CBS apurado sobre nota
    // inexistente no fisco. A SEFAZ real responde cStat 217 para chave desconhecida.
    SefazRetorno retorno = transport.consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO);

    assertThat(retorno.situacao()).isEqualTo(SituacaoSefaz.REJEITADA);
    assertThat(retorno.cStat()).isEqualTo("217");
    assertThat(retorno.protocolo()).isNull();
  }

  @Test
  @DisplayName("enviar deve usar protocolo de fallback quando a chave e nula")
  void enviarShouldFallbackProtocolWhenChaveIsNull() {
    SefazRetorno retorno = transport.enviar("<xml/>", null, SefazAmbiente.HOMOLOGACAO);

    assertThat(retorno.situacao()).isEqualTo(SituacaoSefaz.AUTORIZADA);
    assertThat(retorno.protocolo()).isEqualTo("100000000000001");
  }

  @Test
  @DisplayName("cancelar deve retornar evento vinculado (cStat 135) com protocolo derivado da chave")
  void cancelarShouldReturnLinkedEventWithDerivedProtocol() {
    SefazRetorno retorno = transport.cancelar(
        CHAVE, "Erro na quantidade dos produtos vendidos", "141260000000017",
        SefazAmbiente.HOMOLOGACAO);

    assertThat(retorno.cStat()).isEqualTo("135");
    assertThat(retorno.xMotivo()).isEqualTo("Evento registrado e vinculado a NF-e");
    assertThat(retorno.protocolo()).isEqualTo("1" + CHAVE.substring(0, 14));
    assertThat(retorno.dataAutorizacao()).isNotNull();
  }
}
