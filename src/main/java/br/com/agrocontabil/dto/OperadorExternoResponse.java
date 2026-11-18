package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Operador de fora do Brasil que recebe dado pessoal do AgroConIA (LGPD arts. 9º, V, e 33, #498):
 * o que ele faz, o que recebe, onde, por quanto tempo e como o titular exerce os direitos.
 *
 * @param nome                nome do operador
 * @param servico             o serviço que ele presta ao AgroConIA
 * @param localizacao         onde ficam os dados no operador
 * @param finalidade          para que os dados são enviados
 * @param dadosEnviados       o que sai do AgroConIA para o operador
 * @param dadosNaoEnviados    o que não sai
 * @param baseTransferencia   hipótese do art. 33 da LGPD em que a transferência se apoia e as
 *                            garantias que o contrato do operador oferece
 * @param retencao            por quanto tempo o operador guarda os dados
 * @param comoExercerDireitos como o titular exerce os direitos e o que a exclusão da conta não
 *                            alcança
 */
@Schema(description = "Operador de fora do Brasil que recebe dado pessoal (LGPD, arts. 9º e 33)")
public record OperadorExternoResponse(
    @Schema(description = "Nome do operador", example = "Resend")
    String nome,
    @Schema(description = "O serviço que o operador presta ao AgroConIA")
    String servico,
    @Schema(description = "Onde ficam os dados no operador")
    String localizacao,
    @Schema(description = "Para que os dados são enviados")
    String finalidade,
    @Schema(description = "O que sai do AgroConIA para o operador")
    List<String> dadosEnviados,
    @Schema(description = "O que não sai do AgroConIA")
    List<String> dadosNaoEnviados,
    @Schema(description = "Hipótese do art. 33 da LGPD e garantias do contrato do operador")
    String baseTransferencia,
    @Schema(description = "Por quanto tempo o operador guarda os dados")
    String retencao,
    @Schema(description = "Como o titular exerce os direitos e o que a exclusão não alcança")
    String comoExercerDireitos) {
}
