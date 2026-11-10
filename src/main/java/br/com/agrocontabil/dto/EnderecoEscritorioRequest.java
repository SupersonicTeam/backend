package br.com.agrocontabil.dto;

import br.com.agrocontabil.validation.SlugEscritorio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Endereço próprio do escritório, {@code {slug}.agroconia.com.br} (issue #464), gravado pelo
 * administrador.
 *
 * @param slug                    rótulo do subdomínio; normalizado (sem espaços nas pontas, em
 *                                minúsculas) antes da validação
 * @param cienteExposicaoPublica  o administrador leu que o endereço fica público e permanente nos
 *                                registros de certificados da internet; obrigatório e
 *                                {@code true}
 */
@Schema(description = "Endereço próprio do escritório (subdomínio)")
public record EnderecoEscritorioRequest(
    @Schema(description = "Rótulo do subdomínio: de 3 a 32 caracteres, letras minúsculas sem "
        + "acento, números e hífen", example = "ruraloeste")
    @NotNull @SlugEscritorio String slug,
    @Schema(description = "Ciência de que o endereço fica público e permanente nos registros de "
        + "certificados da internet (não use o nome de uma pessoa); precisa ser true",
        example = "true")
    @NotNull
    @AssertTrue(message = "Confirme que leu o aviso: o endereço fica público e permanente.")
    Boolean cienteExposicaoPublica) {
}
