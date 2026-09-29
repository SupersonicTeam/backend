package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pergunta à Ceres, a assistente fiscal (issue #142).
 *
 * <p>O tamanho é limitado: além de custo e latência, pergunta longa é o veículo típico de prompt
 * injection elaborado (issue #144).
 *
 * @param pergunta texto da pergunta (1 a {@value #TAMANHO_MAXIMO} caracteres)
 */
@Schema(description = "Pergunta a Ceres, a assistente fiscal")
public record AssistenteChatRequest(

    @Schema(description = "Pergunta em linguagem natural",
        example = "Quanto comprei de fertilizante no primeiro semestre?")
    @NotBlank(message = "A pergunta e obrigatoria.")
    @Size(max = AssistenteChatRequest.TAMANHO_MAXIMO,
        message = "A pergunta deve ter no maximo " + AssistenteChatRequest.TAMANHO_MAXIMO
            + " caracteres.")
    String pergunta) {

  /** Tamanho máximo da pergunta, em caracteres. */
  public static final int TAMANHO_MAXIMO = 1000;
}
