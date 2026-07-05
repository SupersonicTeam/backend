package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO agregador do dashboard financeiro do produtor (issue Agrocontabil#65 — Sprint 5).
 *
 * <p>Consolida, para o produtor autenticado e um período mensal, os indicadores já apurados de
 * IBS/CBS ({@link ApuracaoResponse} via {@code ApuracaoService}), a série de volume de notas dos
 * últimos meses (entradas × saídas), a distribuição da carga tributária (apenas IBS/CBS — os
 * demais tributos não são modelados) e as últimas notas. É <strong>somente leitura</strong> e
 * <strong>reúsa</strong> as agregações existentes — não recomputa somas de tributo.
 *
 * <p><strong>Privacidade (LGPD):</strong> o resumo do produtor expõe apenas nome, tipo e UF —
 * nunca o CPF/CNPJ completo. Nenhuma entidade JPA é exposta.
 *
 * @param produtor              resumo do produtor autenticado (sem documento)
 * @param periodo              período de referência dos KPIs no formato {@code YYYY-MM}
 * @param kpis                 indicadores consolidados de IBS/CBS do período
 * @param notasPorMes          série mensal de volume de notas (entradas × saídas), 6 meses
 * @param distribuicaoImpostos composição da carga tributária do período (IBS × CBS)
 * @param ultimasNotas         últimas notas do produtor, mais recentes primeiro
 */
@Schema(description = "Painel financeiro consolidado do produtor em um período")
public record DashboardResponse(

    @Schema(description = "Resumo do produtor autenticado (sem CPF/CNPJ)")
    ProdutorResumo produtor,

    @Schema(description = "Periodo de referencia dos KPIs (YYYY-MM)", example = "2026-07")
    String periodo,

    @Schema(description = "Indicadores consolidados de IBS/CBS do periodo")
    DashboardKpis kpis,

    @Schema(description = "Volume de notas por mes (entradas x saidas), ultimos 6 meses")
    List<NotaPorMes> notasPorMes,

    @Schema(description = "Distribuicao da carga tributaria do periodo (apenas IBS/CBS)")
    DistribuicaoImpostos distribuicaoImpostos,

    @Schema(description = "Ultimas notas do produtor, mais recentes primeiro")
    List<NotaResumo> ultimasNotas) {

  /**
   * Resumo público do produtor exibido no dashboard, sem dados sensíveis.
   *
   * @param nome         nome/razão social do produtor
   * @param tipoProdutor tipo do produtor (ex.: PESSOA_FISICA/PESSOA_JURIDICA); nulo se não definido
   * @param uf           unidade federativa (sigla de 2 letras); nula se não cadastrada
   */
  @Schema(description = "Resumo do produtor (sem documento)")
  public record ProdutorResumo(

      @Schema(description = "Nome/razao social do produtor", example = "Fazenda Boa Vista")
      String nome,

      @Schema(description = "Tipo do produtor", example = "PESSOA_FISICA")
      String tipoProdutor,

      @Schema(description = "UF do produtor", example = "PR")
      String uf) {}

  /**
   * Indicadores consolidados de IBS/CBS do período, espelhando a apuração mensal (débito das
   * saídas autorizadas, crédito das entradas importadas e o saldo devido).
   *
   * @param debitoIbs  débito de IBS das saídas autorizadas do período
   * @param debitoCbs  débito de CBS das saídas autorizadas do período
   * @param creditoIbs crédito de IBS das entradas importadas do período
   * @param creditoCbs crédito de CBS das entradas importadas do período
   * @param saldoIbs   saldo de IBS a recolher ({@code débito − crédito})
   * @param saldoCbs   saldo de CBS a recolher ({@code débito − crédito})
   * @param totalNotas quantidade de notas consideradas na apuração do período
   */
  @Schema(description = "KPIs consolidados de IBS/CBS do periodo")
  public record DashboardKpis(

      @Schema(description = "Debito de IBS das saidas autorizadas", example = "150.00")
      BigDecimal debitoIbs,

      @Schema(description = "Debito de CBS das saidas autorizadas", example = "1350.00")
      BigDecimal debitoCbs,

      @Schema(description = "Credito de IBS das entradas importadas", example = "30.00")
      BigDecimal creditoIbs,

      @Schema(description = "Credito de CBS das entradas importadas", example = "270.00")
      BigDecimal creditoCbs,

      @Schema(description = "Saldo de IBS a recolher (debito - credito)", example = "120.00")
      BigDecimal saldoIbs,

      @Schema(description = "Saldo de CBS a recolher (debito - credito)", example = "1080.00")
      BigDecimal saldoCbs,

      @Schema(description = "Quantidade de notas consideradas no periodo", example = "8")
      long totalNotas) {}

  /**
   * Ponto da série mensal de volume de notas: quantidade de entradas (importadas) e saídas
   * (próprias) autorizadas no mês.
   *
   * @param periodo  mês no formato {@code YYYY-MM}
   * @param entradas quantidade de notas de entrada (IMPORTADA) autorizadas no mês
   * @param saidas   quantidade de notas de saída (PROPRIA) autorizadas no mês
   */
  @Schema(description = "Volume de notas de um mes (entradas x saidas)")
  public record NotaPorMes(

      @Schema(description = "Mes (YYYY-MM)", example = "2026-07")
      String periodo,

      @Schema(description = "Notas de entrada (importadas) autorizadas no mes", example = "3")
      long entradas,

      @Schema(description = "Notas de saida (proprias) autorizadas no mes", example = "5")
      long saidas) {}

  /**
   * Distribuição da carga tributária do período, restrita aos tributos modelados (IBS e CBS).
   * Baseada no débito do período (o quanto de cada tributo foi destacado nas saídas). FUNRURAL,
   * ICMS e IPI não são modelados no escopo do TCC.
   *
   * @param ibs débito de IBS do período
   * @param cbs débito de CBS do período
   */
  @Schema(description = "Composicao da carga tributaria do periodo (apenas IBS/CBS)")
  public record DistribuicaoImpostos(

      @Schema(description = "Debito de IBS do periodo", example = "150.00")
      BigDecimal ibs,

      @Schema(description = "Debito de CBS do periodo", example = "1350.00")
      BigDecimal cbs) {}

  /**
   * Resumo de uma nota fiscal para a lista de últimas notas do dashboard. Não expõe a entidade
   * JPA; a {@code contraparte} é o destinatário (notas próprias) ou o emitente (notas importadas).
   *
   * @param id             identificador da nota
   * @param numero         número sequencial da NF-e
   * @param origem         origem da nota (PROPRIA/IMPORTADA)
   * @param contraparte    nome do destinatário (PROPRIA) ou do emitente (IMPORTADA)
   * @param dataEmissao    data/hora de emissão
   * @param valorTotalNota valor total da nota
   * @param status         status do ciclo de vida da nota
   */
  @Schema(description = "Resumo de uma nota fiscal na lista de ultimas notas")
  public record NotaResumo(

      @Schema(description = "Identificador da nota")
      UUID id,

      @Schema(description = "Numero sequencial da NF-e", example = "1024")
      Long numero,

      @Schema(description = "Origem da nota", example = "PROPRIA")
      String origem,

      @Schema(description = "Destinatario (PROPRIA) ou emitente (IMPORTADA)",
          example = "Cooperativa Agro Ltda")
      String contraparte,

      @Schema(description = "Data/hora de emissao")
      OffsetDateTime dataEmissao,

      @Schema(description = "Valor total da nota", example = "12500.00")
      BigDecimal valorTotalNota,

      @Schema(description = "Status da nota", example = "AUTORIZADA")
      String status) {}
}
