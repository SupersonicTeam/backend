package br.com.agrocontabil.dto;

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
public record PoliticaDadosResponse(
    String versao,
    String controlador,
    String contatoEncarregado,
    List<String> dadosColetados,
    List<String> finalidades,
    List<String> basesLegais,
    List<String> compartilhamento,
    List<String> retencao,
    List<String> direitos,
    String comoExercer) {
}
