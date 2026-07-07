package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.TipoGuiaFiscal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO de entrada para o Contador anexar uma guia fiscal a um produtor (issue #74).
 *
 * <p>O {@code produtorId} vem explícito no payload — não há autenticação de Contador no
 * backend ainda, então o produtor destinatário não pode ser derivado do contexto de segurança
 * (diferente do restante da API, onde o produtor é sempre o autenticado).
 *
 * @param produtorId    identificador do produtor destinatário da guia
 * @param tipoGuia      tipo da guia (ICMS/FUNRURAL/DARF/GPS/OUTRO)
 * @param dataVencimento data de vencimento da guia
 * @param valor         valor da guia
 * @param observacoes   observações livres do Contador; opcional
 */
@Schema(description = "Dados para o Contador anexar uma guia fiscal a um produtor")
public record GuiaFiscalRequest(

    @Schema(description = "Identificador do produtor destinatario da guia")
    @NotNull(message = "O produtorId e obrigatorio.")
    UUID produtorId,

    @Schema(description = "Tipo da guia fiscal", example = "ICMS")
    @NotNull(message = "O tipoGuia e obrigatorio.")
    TipoGuiaFiscal tipoGuia,

    @Schema(description = "Data de vencimento da guia", example = "2026-08-20")
    @NotNull(message = "A dataVencimento e obrigatoria.")
    LocalDate dataVencimento,

    @Schema(description = "Valor da guia", example = "1250.00")
    @NotNull(message = "O valor e obrigatorio.")
    @DecimalMin(value = "0.01", message = "O valor deve ser maior que zero.")
    BigDecimal valor,

    @Schema(description = "Observacoes livres do Contador", example = "Referente a agosto/2026")
    @Size(max = 255, message = "As observacoes devem ter no maximo 255 caracteres.")
    String observacoes) {}
