package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Termos de uso do AgroConIA (#498), em formato que o frontend exibe sem HTML vindo do backend.
 *
 * @param versao      versão dos termos: a mesma que o cadastro grava na credencial com o aceite
 *                    ({@code app.termos.versao}); é a data do texto
 * @param controlador quem oferece o serviço e decide sobre os dados (a GWNext)
 * @param contato     canal de contato para dúvidas e pedidos
 * @param secoes      as seções do texto, na ordem em que a tela as mostra
 */
@Schema(description = "Termos de uso do AgroConIA")
public record TermosUsoResponse(
    @Schema(description = "Versão dos termos, a mesma gravada no aceite do cadastro",
        example = "2026-11-24")
    String versao,
    @Schema(description = "Quem oferece o serviço", example = "GWNext")
    String controlador,
    @Schema(description = "Contato para dúvidas e pedidos", example = "contato@gwnext.com.br")
    String contato,
    @Schema(description = "Seções do texto, na ordem de exibição")
    List<SecaoTermosResponse> secoes) {
}
