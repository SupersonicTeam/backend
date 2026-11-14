package br.com.agrocontabil.dto;

import br.com.agrocontabil.validation.CodigoIbgeCoerenteComUf;
import br.com.agrocontabil.validation.EnderecoComCodigoIbge;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Endereço postal do escritório (issue #519), completo: no formato do endereço do produtor
 * ({@code EnderecoProdutor}, #88), com a UF. "Postal" o separa do endereço próprio na internet
 * ({@code {slug}.agroconia.com.br}, #464).
 *
 * @param cep                 8 dígitos, sem formatação
 * @param logradouro          rua, avenida etc.
 * @param numero              número do imóvel ({@code S/N} se não houver)
 * @param complemento         opcional
 * @param bairro              bairro
 * @param codigoMunicipioIbge 7 dígitos; os 2 primeiros são o código da UF
 * @param municipio           nome do município
 * @param uf                  sigla da UF
 */
@Schema(description = "Endereço postal do escritório, completo")
@CodigoIbgeCoerenteComUf
public record EnderecoPostalRequest(
    @NotNull(message = "Informe o CEP.")
    @Pattern(regexp = "\\d{8}", message = "Informe o CEP só com dígitos (8 dígitos).")
    @Schema(description = "CEP, 8 dígitos sem formatação", example = "85810000")
    String cep,

    @NotBlank(message = "Informe o logradouro.")
    @Size(max = 60, message = "O logradouro tem no máximo 60 caracteres.")
    @Schema(description = "Rua, avenida etc.", example = "Rua Paraná")
    String logradouro,

    @NotBlank(message = "Informe o número (ou S/N).")
    @Size(max = 60, message = "O número tem no máximo 60 caracteres.")
    @Schema(description = "Número do imóvel; S/N se não houver", example = "1200")
    String numero,

    @Size(max = 60, message = "O complemento tem no máximo 60 caracteres.")
    @Schema(description = "Complemento, opcional", example = "Sala 3", nullable = true)
    String complemento,

    @NotBlank(message = "Informe o bairro.")
    @Size(max = 60, message = "O bairro tem no máximo 60 caracteres.")
    @Schema(description = "Bairro", example = "Centro")
    String bairro,

    @NotNull(message = "Informe o código IBGE do município.")
    @Pattern(regexp = "\\d{7}",
        message = "Informe o código IBGE do município com 7 dígitos.")
    @Schema(description = "Código IBGE do município, 7 dígitos", example = "4104808")
    String codigoMunicipioIbge,

    @NotBlank(message = "Informe o município.")
    @Size(max = 60, message = "O município tem no máximo 60 caracteres.")
    @Schema(description = "Município", example = "Cascavel")
    String municipio,

    @NotNull(message = "Informe a UF.")
    @Pattern(regexp = "AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC"
        + "|SP|SE|TO", message = "Informe a sigla de uma UF do Brasil, em maiúsculas.")
    @Schema(description = "Sigla da UF", example = "PR")
    String uf) implements EnderecoComCodigoIbge {
}
