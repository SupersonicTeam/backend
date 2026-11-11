package br.com.agrocontabil.dto;

import br.com.agrocontabil.validation.ValidCpfCnpj;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Edição do escritório pelo administrador (issues #228 e #519). O que não vem fica como está.
 *
 * @param nome     nome do escritório
 * @param cnpj     CNPJ só com dígitos; ausente mantém o atual, vazio apaga (contador autônomo). O
 *                 empresarial não fica sem CNPJ (422)
 * @param telefone telefone do escritório, só dígitos (10 ou 11); ausente ou vazio mantém o atual
 *                 (vazio = não informado, como no cadastro). Não se apaga pelo PATCH (#519)
 * @param endereco endereço postal completo; ausente mantém o atual. Substitui o endereço inteiro e
 *                 não se apaga pelo PATCH (#519)
 */
@Schema(description = "Edição do escritório pelo administrador; o que não vem fica como está")
public record EscritorioRequest(
    @Schema(description = "Nome do escritório", example = "Escritório Campo Verde")
    @NotBlank @Size(max = 255) String nome,
    @Schema(description = "CNPJ só com dígitos; vazio apaga (contador autônomo). O empresarial não"
        + " fica sem CNPJ (422)")
    @Pattern(regexp = "\\d{14}|", message = "Informe o CNPJ com 14 dígitos (vazio apaga)") @ValidCpfCnpj
    String cnpj,
    @Schema(description = "Telefone do escritório, só dígitos (10 ou 11); ausente ou vazio mantém"
        + " o atual", example = "4533330000", nullable = true)
    @Pattern(regexp = "\\d{10,11}|",
        message = "Informe o telefone do escritório só com dígitos: DDD e número (10 ou 11).")
    String telefone,
    @Schema(description = "Endereço postal completo; ausente mantém o atual", nullable = true)
    @Valid
    EnderecoPostalRequest endereco) {

  /**
   * Edição só de nome e CNPJ, como antes da #519.
   *
   * @param nome nome do escritório
   * @param cnpj CNPJ só com dígitos; ausente mantém, vazio apaga
   */
  public EscritorioRequest(String nome, String cnpj) {
    this(nome, cnpj, null, null);
  }
}
