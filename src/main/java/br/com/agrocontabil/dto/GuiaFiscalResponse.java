package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO de saída de uma guia fiscal (issue #74). Nunca expõe a entidade JPA nem o
 * {@code Produtor} completo — apenas o {@code produtorId}.
 *
 * @param id             identificador da guia
 * @param produtorId     identificador do produtor destinatário
 * @param tipoGuia       tipo da guia (ICMS/FUNRURAL/DARF/GPS/OUTRO)
 * @param dataVencimento data de vencimento
 * @param valor          valor da guia
 * @param observacoes    observações livres do Contador; pode ser nulo
 * @param status         status do ciclo de vida (PENDENTE/PAGA/VENCIDA)
 * @param dataPagamento  data/hora em que foi marcada como paga; nula enquanto pendente/vencida
 * @param createdAt      data/hora de criação
 */
@Schema(description = "Guia fiscal anexada pelo Contador para um produtor")
public record GuiaFiscalResponse(

    @Schema(description = "Identificador da guia")
    UUID id,

    @Schema(description = "Identificador do produtor destinatario")
    UUID produtorId,

    @Schema(description = "Tipo da guia fiscal", example = "ICMS")
    String tipoGuia,

    @Schema(description = "Data de vencimento da guia", example = "2026-08-20")
    LocalDate dataVencimento,

    @Schema(description = "Valor da guia", example = "1250.00")
    BigDecimal valor,

    @Schema(description = "Observacoes livres do Contador", example = "Referente a agosto/2026")
    String observacoes,

    @Schema(description = "Status do ciclo de vida da guia", example = "PENDENTE")
    String status,

    @Schema(description = "Data/hora em que a guia foi marcada como paga; nula se nao paga")
    OffsetDateTime dataPagamento,

    @Schema(description = "Data/hora de criacao da guia")
    OffsetDateTime createdAt) {}
