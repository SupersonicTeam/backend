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
   * Monta o DTO do fluxo de caixa em R$ do período (issue Agrocontabil#71) a partir dos valores
   * <strong>já calculados</strong> pelo {@code DashboardService} — este método só empacota, sem
   * derivar saldo/lucro (regra de negócio pertence à camada de serviço).
   *
   * @param totalEntradas soma do valor total das notas próprias (vendas) autorizadas no período
   * @param totalSaidas   soma do valor total das notas importadas (compras) autorizadas no período
   * @param saldo         fluxo de caixa bruto do período ({@code totalEntradas − totalSaidas})
   * @param lucroEstimado saldo líquido do IBS/CBS a recolher no período
   * @return resumo financeiro do dashboard
   */
  public static FinanceiroResumo toFinanceiroResumo(
      BigDecimal totalEntradas, BigDecimal totalSaidas, BigDecimal saldo,
      BigDecimal lucroEstimado) {
    return new FinanceiroResumo(totalEntradas, totalSaidas, saldo, lucroEstimado);
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
