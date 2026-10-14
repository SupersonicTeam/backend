package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Alerta tributário do produtor (issue #140, RN010).
 *
 * @param id             id do alerta
 * @param tipo           {@code VENCIMENTO_GUIA}, {@code FECHAMENTO_APURACAO} ou
 *                       {@code PRAZO_CANCELAMENTO_NFE}
 * @param referenciaId   guia, nota ou apuração a que se refere (do próprio produtor)
 * @param descricao      texto curto montado pelo backend
 * @param dataLimite     último dia para agir
 * @param diasRestantes  dias de hoje até a data-limite (0 = vence hoje)
 */
@Schema(description = "Alerta tributario: algo vence ou fecha em menos de 5 dias")
public record AlertaTributarioResponse(
    UUID id,
    @Schema(example = "VENCIMENTO_GUIA") String tipo,
    UUID referenciaId,
    @Schema(example = "Guia de FUNRURAL de R$ 1.840,55") String descricao,
    LocalDate dataLimite,
    @Schema(example = "3") long diasRestantes) {}
