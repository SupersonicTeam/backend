package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Aviso não bloqueante sobre uma nota recém-gravada (issue #389). Não é erro: a nota foi salva,
 * mas algo nela não vai valer na emissão e a tela não deve mostrar o resultado como definitivo.
 */
@Schema(description = "Aviso nao bloqueante sobre a nota gravada (a nota foi salva)")
public record AvisoNotaResponse(

    @Schema(description = "Codigo estavel do aviso", example = "CCLASSTRIB_NAO_ADMITIDO")
    String codigo,

    @Schema(description = "Numero do item a que o aviso se refere (nulo se for da nota inteira)",
        example = "1")
    Integer numeroItem,

    @Schema(description = "Campo do item a corrigir", example = "cClassTrib")
    String campo,

    @Schema(description = "Texto para o usuario")
    String mensagem) {}
