package br.com.agrocontabil.dto;

import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Edição do escritório pelo administrador (issue #228).
 *
 * @param nome nome do escritório
 * @param cnpj CNPJ só com dígitos; ausente mantém o atual, vazio apaga (contador autônomo)
 */
@Schema(description = "Edição do escritório pelo administrador")
public record EscritorioRequest(
    @Schema(description = "Nome do escritório", example = "Escritório Campo Verde")
    @NotBlank @Size(max = 255) String nome,
    @Schema(description = "CNPJ só com dígitos; vazio apaga (contador autônomo)")
    @Pattern(regexp = "\\d{14}|", message = "Informe o CNPJ com 14 dígitos (vazio apaga)") @ValidCpfCnpj
    String cnpj) {
}
