package br.com.agrocontabil.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resposta de uma inconsistência de validação no histórico de uma invoice fiscal (issue #22).
 *
 * <p>Espelha o {@link br.com.agrocontabil.common.ErrorDetail} persistido, acrescido do
 * {@code validationBatch} (agrupa as inconsistências de uma mesma tentativa) e do
 * {@code validatedAt}. Campos opcionais nulos são omitidos do JSON.
 *
 * @param validationBatch identificador da tentativa de validação (agrupa o histórico)
 * @param code          código estável do error
 * @param field         field afetado (ex.: {@code item[1].ncm}); opcional
 * @param message       mensagem amigável ao usuário
 * @param suggestion    sugestão de correção; opcional
 * @param rejectedValue valor recebido; opcional
 * @param expectedValue valor esperado; opcional
 * @param validatedAt    instante em que a validação foi executada
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Inconsistência de validação registrada para uma nota fiscal")
public record InvoiceValidationResponse(
    @JsonProperty("loteValidacao") UUID validationBatch,
    String code,
    String field,
    String message,
    String suggestion,
    String rejectedValue,
    String expectedValue,
    OffsetDateTime validatedAt) {
}
