package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Corpo opcional do encerramento de um vínculo (issue #402). A chamada sem corpo, ou com o motivo
 * em branco, encerra sem motivo.
 *
 * <p>O motivo é texto livre e pode citar dado pessoal: nunca vai para log, para a auditoria nem
 * para o {@code rejectedValue} de um erro de validação.
 *
 * @param motivo motivo do encerramento, opcional (até 500 caracteres)
 */
@Schema(description = "Corpo opcional do encerramento do vínculo")
public record EncerramentoRequest(
    @Sensivel
    @Size(max = EncerramentoRequest.MOTIVO_MAXIMO, message = "O motivo aceita até 500 caracteres.")
    @Schema(description = "Motivo do encerramento (texto livre, opcional, até 500 caracteres). "
        + "Em branco equivale a não informar.", example = "Mudança de escritório")
    String motivo) {

  /** Tamanho da coluna {@code motivo_encerramento}. */
  public static final int MOTIVO_MAXIMO = 500;

  /**
   * Motivo normalizado.
   *
   * @param request corpo, que pode ser nulo (chamada sem corpo)
   * @return o motivo sem espaços nas pontas, ou nulo se ausente ou em branco
   */
  public static String motivoDe(EncerramentoRequest request) {
    if (request == null || request.motivo() == null || request.motivo().isBlank()) {
      return null;
    }
    return request.motivo().strip();
  }

  /** Sem o texto: o motivo pode ter dado pessoal e um {@code toString} chega a log. */
  @Override
  public String toString() {
    return "EncerramentoRequest[motivo=" + (motivo == null ? "ausente" : "informado") + "]";
  }
}
