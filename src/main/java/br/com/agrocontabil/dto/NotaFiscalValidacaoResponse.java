package br.com.agrocontabil.dto;

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

    @Schema(description = "Identificador da tentativa de validacao (agrupa o historico)",
        example = "a1b2c3d4-e5f6-7890-ab12-cd34ef56ab78")
    UUID loteValidacao,

    @Schema(description = "Codigo estavel do erro", example = "NCM_INVALIDO")
    String code,

    @Schema(description = "Campo afetado (opcional)", example = "item[1].ncm")
    String field,

    @Schema(description = "Mensagem amigavel ao usuario",
        example = "O NCM informado nao existe na tabela de referencia.")
    String message,

    @Schema(description = "Sugestao de correcao (opcional)",
        example = "Consulte a tabela NCM vigente e informe um codigo valido de 8 digitos.")
    String suggestion,

    @Schema(description = "Valor recebido (opcional)", example = "00000000")
    String rejectedValue,

    @Schema(description = "Valor esperado (opcional)", example = "NCM valido de 8 digitos")
    String expectedValue,

    @Schema(description = "Instante em que a validacao foi executada",
        example = "2026-06-24T10:15:30-03:00")
    OffsetDateTime validadoEm) {
}
