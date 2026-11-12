package br.com.agrocontabil.dto;

import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Dados para tornar empresarial o escritório pessoal (issue #519), com as regras do cadastro
 * empresarial. O endereço próprio continua no {@code PUT /api/escritorio/endereco}.
 *
 * @param nome     nome do escritório
 * @param cnpj     CNPJ, 14 dígitos com DV válido (não é único, #520)
 * @param telefone telefone do escritório, só dígitos (10 ou 11)
 * @param endereco endereço postal completo
 */
@Schema(description = "Dados para tornar o escritório pessoal em empresarial")
public record TornarEmpresarialRequest(
    @NotBlank(message = "Informe o nome do escritório.")
    @Size(max = 255, message = "O nome do escritório tem no máximo 255 caracteres.")
    @Schema(description = "Nome do escritório", example = "Contabilidade Campo Verde")
    String nome,

    @NotNull(message = "Informe o CNPJ do escritório.")
    @Pattern(regexp = "\\d{14}", message = "Informe o CNPJ do escritório só com dígitos (14).")
    @ValidCpfCnpj
    @Schema(description = "CNPJ só com dígitos", example = "11444777000161")
    String cnpj,

    @NotNull(message = "Informe o telefone do escritório.")
    @Pattern(regexp = "\\d{10,11}",
        message = "Informe o telefone do escritório só com dígitos: DDD e número (10 ou 11).")
    @Schema(description = "DDD e número, só dígitos (10 ou 11)", example = "4533330000")
    String telefone,

    @NotNull(message = "Informe o endereço do escritório.")
    @Valid
    @Schema(description = "Endereço postal completo")
    EnderecoPostalRequest endereco) {

  /** Sem os dados do escritório: o nome e o endereço podem identificar uma pessoa (LGPD). */
  @Override
  public String toString() {
    return "TornarEmpresarialRequest[***]";
  }
}
