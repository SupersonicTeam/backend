package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Política de tratamento de dados pessoais (LGPD art. 9º, issue #148) — o que o titular tem
 * direito de saber, em formato que o frontend exibe.
 *
 * @param versao             versão da política
 * @param controlador        quem decide sobre o tratamento
 * @param contatoEncarregado canal do encarregado (DPO)
 * @param dadosColetados     categorias de dado pessoal tratadas
 * @param finalidades        para que cada dado é usado
 * @param basesLegais        base legal de cada tratamento (art. 7º)
 * @param compartilhamento   com quem os dados são compartilhados
 * @param retencao           por quanto tempo cada dado fica guardado
 * @param direitos           direitos do titular (art. 18)
 * @param comoExercer        como exercer os direitos
 */
@Schema(description = "Política de tratamento de dados pessoais (LGPD, art. 9º)")
public record PoliticaDadosResponse(
    @Schema(description = "Versão da política", example = "1.0")
    String versao,
    @Schema(description = "Quem decide sobre o tratamento dos dados")
    String controlador,
    @Schema(description = "Canal do encarregado de dados")
    String contatoEncarregado,
    @Schema(description = "Categorias de dado pessoal tratadas")
    List<String> dadosColetados,
    @Schema(description = "Para que cada dado é usado")
    List<String> finalidades,
    @Schema(description = "Base legal de cada tratamento")
    List<String> basesLegais,
    @Schema(description = "Com quem os dados são compartilhados")
    List<String> compartilhamento,
    @Schema(description = "Por quanto tempo cada dado fica guardado")
    List<String> retencao,
    @Schema(description = "Direitos do titular")
    List<String> direitos,
    @Schema(description = "Como exercer os direitos")
    String comoExercer) {
}
