package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.StatusNotaFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * DTO de saída do cancelamento de uma NF-e (issue #21 — RN006).
 *
 * <p>Reflete o resultado do cancelamento: status terminal {@code CANCELADA}, os dados do
 * evento (data, protocolo e motivo) e o retorno do fisco (cStat e motivo do evento).
 *
 * @param chaveAcesso            chave de acesso da NF-e (44 dígitos)
 * @param status                status resultante da nota (CANCELADA)
 * @param dataCancelamento      instante do cancelamento
 * @param protocoloCancelamento protocolo do evento de cancelamento retornado pela SEFAZ
 * @param motivoCancelamento    justificativa informada no cancelamento
 * @param sefazCstat            código de status do evento retornado pela SEFAZ (ex.: "135")
 * @param sefazMotivo           descrição textual do status do evento
 */
@Schema(description = "Resultado do cancelamento de uma NF-e")
public record CancelamentoResponse(

    @Schema(description = "Chave de acesso da NF-e (44 digitos)",
        example = "41262612345678000195550010000000011000000012")
    String chaveAcesso,

    @Schema(description = "Status resultante da nota apos o cancelamento", example = "CANCELADA")
    StatusNotaFiscal status,

    @Schema(description = "Data e hora do cancelamento", example = "2026-06-20T14:30:00-03:00")
    OffsetDateTime dataCancelamento,

    @Schema(description = "Protocolo do evento de cancelamento retornado pela SEFAZ",
        example = "141260000000017")
    String protocoloCancelamento,

    @Schema(description = "Justificativa informada no cancelamento",
        example = "Cancelamento por erro na quantidade dos produtos vendidos")
    String motivoCancelamento,

    @Schema(description = "Codigo de status do evento retornado pela SEFAZ", example = "135")
    String sefazCstat,

    @Schema(description = "Descricao textual do status do evento",
        example = "Evento registrado e vinculado a NF-e")
    String sefazMotivo) {}
