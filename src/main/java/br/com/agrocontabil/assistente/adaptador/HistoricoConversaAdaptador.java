package br.com.agrocontabil.assistente.adaptador;

import br.com.agrocontabil.assistente.porta.HistoricoConversaPorta;
import br.com.agrocontabil.security.AssinaturaHistoricoConversa;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Liga a porta da assinatura do histórico (#499) ao componente de {@code security}. */
@Component
public class HistoricoConversaAdaptador implements HistoricoConversaPorta {

  private final AssinaturaHistoricoConversa assinatura;

  public HistoricoConversaAdaptador(AssinaturaHistoricoConversa assinatura) {
    this.assinatura = assinatura;
  }

  @Override
  public Optional<String> assinar(UUID produtorId, String carga, String pergunta,
      String resposta) {
    return assinatura.assinar(produtorId, carga.getBytes(StandardCharsets.UTF_8), pergunta,
        resposta);
  }

  @Override
  public Optional<String> conferir(UUID produtorId, String assinaturaRecebida, String pergunta,
      String resposta) {
    return assinatura.conferir(produtorId, assinaturaRecebida, pergunta, resposta)
        .flatMap(HistoricoConversaAdaptador::utf8);
  }

  /** A carga volta como texto só se for UTF-8 válido. */
  private static Optional<String> utf8(byte[] bytes) {
    try {
      return Optional.of(StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString());
    } catch (CharacterCodingException e) {
      return Optional.empty();
    }
  }
}
