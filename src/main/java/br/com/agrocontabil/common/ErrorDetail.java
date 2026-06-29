package br.com.agrocontabil.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Item de error estruturado exposto no contrato da API ({@code errors[]}).
 *
 * <p>Campos opcionais nulos são omitidos do JSON ({@link JsonInclude.Include#NON_NULL}).
 *
 * @param code          código estável do error (ver {@link ErrorCode})
 * @param field         field afetado (ex.: {@code items[0].cfop}); opcional
 * @param message       mensagem amigável ao usuário
 * @param suggestion    sugestão de correção; opcional
 * @param rejectedValue valor recebido; opcional
 * @param expectedValue valor esperado; opcional
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(
    String code,
    String field,
    String message,
    String suggestion,
    String rejectedValue,
    String expectedValue) {

  /** Cria um detalhe apenas com código, field e mensagem (sem extras). */
  public static ErrorDetail of(ErrorCode code, String field, String message) {
    return new ErrorDetail(code.code(), field, message, null, null, null);
  }

  /** Cria um detalhe sem field associado (error de nível de requisição). */
  public static ErrorDetail of(ErrorCode code, String message) {
    return new ErrorDetail(code.code(), null, message, null, null, null);
  }
}
