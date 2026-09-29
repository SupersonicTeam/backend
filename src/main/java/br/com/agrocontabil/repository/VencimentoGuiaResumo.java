package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Guia fiscal a vencer, para a Ceres (issue #142). Sem as observações da guia: texto livre do
 * contador, que pode conter qualquer dado.
 *
 * @param tipo           tipo da guia
 * @param dataVencimento vencimento
 * @param valor          valor da guia
 * @param status         pendente ou vencida (guias pagas não entram)
 */
public record VencimentoGuiaResumo(
    TipoGuiaFiscal tipo, LocalDate dataVencimento, BigDecimal valor, StatusGuiaFiscal status) {}
