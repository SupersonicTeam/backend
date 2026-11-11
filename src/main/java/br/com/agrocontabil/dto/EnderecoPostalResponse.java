package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Endereço postal do escritório na resposta (issue #519).
 *
 * @param cep                 8 dígitos
 * @param logradouro          rua, avenida etc.
 * @param numero              número do imóvel
 * @param complemento         complemento, se houver
 * @param bairro              bairro
 * @param codigoMunicipioIbge código IBGE do município
 * @param municipio           município
 * @param uf                  sigla da UF
 */
@Schema(description = "Endereço postal do escritório")
public record EnderecoPostalResponse(
    @Schema(example = "85810000") String cep,
    @Schema(example = "Rua Paraná") String logradouro,
    @Schema(example = "1200") String numero,
    @Schema(example = "Sala 3", nullable = true) String complemento,
    @Schema(example = "Centro") String bairro,
    @Schema(example = "4104808") String codigoMunicipioIbge,
    @Schema(example = "Cascavel") String municipio,
    @Schema(example = "PR") String uf) {
}
