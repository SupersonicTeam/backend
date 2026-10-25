package br.com.agrocontabil.dto;

import br.com.agrocontabil.validation.ValidCpfCnpj;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Edição do escritório pelo administrador (issue #228).
 *
 * @param nome nome do escritório
 * @param cnpj CNPJ só com dígitos; ausente mantém o atual, vazio apaga (contador autônomo)
 */
public record EscritorioRequest(
    @NotBlank @Size(max = 255) String nome,
    @Pattern(regexp = "\\d{14}|", message = "Informe o CNPJ com 14 digitos (vazio apaga)") @ValidCpfCnpj
    String cnpj) {
}
