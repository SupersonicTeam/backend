package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.ApuracaoResponse;
import br.com.agrocontabil.dto.DashboardResponse.DashboardKpis;
import br.com.agrocontabil.dto.DashboardResponse.DistribuicaoImpostos;
import br.com.agrocontabil.dto.DashboardResponse.FinanceiroResumo;
import br.com.agrocontabil.dto.DashboardResponse.NotaResumo;
import br.com.agrocontabil.dto.DashboardResponse.ProdutorResumo;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ValorNotasAgregado;
import java.math.BigDecimal;

/**
 * Converte entidades e DTOs de domínio nas partes do {@link br.com.agrocontabil.dto.DashboardResponse}
 * (issue Agrocontabil#65 — Sprint 5).
 *
 * <p>Como {@link NotaFiscalMapper}, é uma classe utilitária de métodos estáticos (não é um Spring
 * bean), o que a mantém testável sem contexto Spring. Garante que nenhuma entidade JPA vaze na API
 * e que o resumo do produtor <strong>nunca</strong> exponha o CPF/CNPJ (LGPD).
 */
public final class DashboardMapper {

  private DashboardMapper() {}

  /**
   * Monta o resumo público do produtor (nome, tipo e UF) — sem documento.
   *
   * @param produtor produtor autenticado
   * @return resumo sem dados sensíveis
   */
  public static ProdutorResumo toProdutorResumo(Produtor produtor) {
    return new ProdutorResumo(
        produtor.getNomeCompleto(),
        produtor.getTipoProdutor() != null ? produtor.getTipoProdutor().name() : null,
        produtor.getEstado());
  }

  /**
   * Copia os indicadores consolidados da apuração do período para os KPIs do dashboard, sem
   * recomputar nada — os valores já vêm apurados do {@code ApuracaoService}.
   *
   * @param apuracao apuração (persistida ou projeção ao vivo) do período
   * @return KPIs do dashboard
   */
  public static DashboardKpis toKpis(ApuracaoResponse apuracao) {
    return new DashboardKpis(
        apuracao.debitoIbs(), apuracao.debitoCbs(),
        apuracao.creditoIbs(), apuracao.creditoCbs(),
        apuracao.saldoIbs(), apuracao.saldoCbs(),
        apuracao.totalNotas());
  }

  /**
   * Monta o fluxo de caixa em R$ do período (issue Agrocontabil#71): entradas de caixa vêm do
   * valor total das notas próprias (vendas), saídas de caixa do valor total das importadas
   * (compras). O saldo bruto é {@code entradas − saídas}; o lucro estimado desconta desse saldo o
   * IBS/CBS a recolher do mesmo período (já apurado, sem recomputar).
   *
   * @param valores  agregação de valor total por origem, já com nulos tratados como zero
   * @param apuracao apuração do período (fonte do saldo de IBS/CBS a descontar)
   * @return resumo financeiro do dashboard
   */
  public static FinanceiroResumo toFinanceiroResumo(
      ValorNotasAgregado valores, ApuracaoResponse apuracao) {
    BigDecimal totalEntradas = coalesce(valores.valorPropria());
    BigDecimal totalSaidas = coalesce(valores.valorImportada());
    BigDecimal saldo = totalEntradas.subtract(totalSaidas);
    BigDecimal tributosARecolher = apuracao.saldoIbs().add(apuracao.saldoCbs());
    BigDecimal lucroEstimado = saldo.subtract(tributosARecolher);
    return new FinanceiroResumo(totalEntradas, totalSaidas, saldo, lucroEstimado);
  }

  /** Trata nulo do {@code SUM} (origem sem notas no período) como {@code 0.00}. */
  private static BigDecimal coalesce(BigDecimal valor) {
    return valor != null ? valor : BigDecimal.ZERO;
  }

  /**
   * Deriva a distribuição da carga tributária (IBS × CBS) a partir do débito do período apurado.
   *
   * @param apuracao apuração do período
   * @return composição IBS × CBS do débito
   */
  public static DistribuicaoImpostos toDistribuicaoImpostos(ApuracaoResponse apuracao) {
    return new DistribuicaoImpostos(apuracao.debitoIbs(), apuracao.debitoCbs());
  }

  /**
   * Converte uma nota fiscal em resumo para a lista de últimas notas, resolvendo a contraparte pela
   * origem: destinatário nas notas próprias, emitente nas importadas.
   *
   * @param nota nota fiscal do produtor
   * @return resumo sem exposição da entidade JPA
   */
  public static NotaResumo toNotaResumo(NotaFiscal nota) {
    String contraparte = nota.getOrigem() == OrigemNotaFiscal.IMPORTADA
        ? nota.getEmitenteNome()
        : nota.getDestinatarioNome();
    return new NotaResumo(
        nota.getId(),
        nota.getNumero(),
        nota.getOrigem() != null ? nota.getOrigem().name() : null,
        contraparte,
        nota.getDataEmissao(),
        nota.getValorTotalNota(),
        nota.getStatus() != null ? nota.getStatus().name() : null);
  }
}
