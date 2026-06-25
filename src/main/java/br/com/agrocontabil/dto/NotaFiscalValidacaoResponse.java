package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.NotaFiscalValidacao;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resposta de uma inconsistência de validação no histórico de uma nota fiscal (issue #22).
 *
 * <p>Espelha o {@link br.com.agrocontabil.common.ErrorDetail} persistido, acrescido do
 * {@code loteValidacao} (agrupa as inconsistências de uma mesma tentativa) e do
 * {@code validadoEm}. Campos opcionais nulos são omitidos do JSON.
 *
 * @param loteValidacao identificador da tentativa de validação (agrupa o histórico)
 * @param code          código estável do erro
 * @param field         campo afetado (ex.: {@code item[1].ncm}); opcional
 * @param message       mensagem amigável ao usuário
 * @param suggestion    sugestão de correção; opcional
 * @param rejectedValue valor recebido; opcional
 * @param expectedValue valor esperado; opcional
 * @param validadoEm    instante em que a validação foi executada
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Inconsistência de validação registrada para uma nota fiscal")
public record NotaFiscalValidacaoResponse(
    UUID loteValidacao,
    String code,
    String field,
    String message,
    String suggestion,
    String rejectedValue,
    String expectedValue,
    OffsetDateTime validadoEm) {

  /** Converte a entidade persistida no DTO de resposta. */
  public static NotaFiscalValidacaoResponse from(NotaFiscalValidacao validacao) {
    return new NotaFiscalValidacaoResponse(
        validacao.getLoteValidacao(),
        validacao.getCode(),
        validacao.getField(),
        validacao.getMessage(),
        validacao.getSuggestion(),
        validacao.getRejectedValue(),
        validacao.getExpectedValue(),
        validacao.getValidadoEm());
  }
}
