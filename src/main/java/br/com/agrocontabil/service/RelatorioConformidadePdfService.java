package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.RelatorioConformidadeResponse;
import br.com.agrocontabil.exception.RelatorioGenerationException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Gera o PDF do relatório de conformidade tributária (issue Agrocontabil#32 — UC004) via
 * JasperReports, seguindo o mesmo padrão do {@code DanfePdfService}: template {@code .jrxml}
 * compilado uma única vez (cache) e formatação de valores centralizada aqui.
 *
 * <p>O relatório é um resumo (sem coleção de itens de detalhe); o preenchimento usa um
 * {@link JREmptyDataSource} de uma linha e todo o conteúdo consolidado vive na banda
 * {@code title} (com o rodapé na banda {@code pageFooter}).
 *
 * <p><strong>Escopo:</strong> relatório informativo para o TCC; não substitui a apuração
 * oficial da SEFAZ.
 */
@Service
public class RelatorioConformidadePdfService {

  private static final Logger log =
      LoggerFactory.getLogger(RelatorioConformidadePdfService.class);

  private static final String TEMPLATE = "reports/relatorio-conformidade.jrxml";
  private static final Locale BR = Locale.of("pt", "BR");

  /** Cache do template compilado (double-checked locking). */
  private volatile JasperReport compiledReport;

  /**
   * Gera o PDF do relatório de conformidade a partir do consolidado do período.
   *
   * @param relatorio     consolidado de débitos/créditos/saldo do período
   * @param nomeProdutor  nome/razão social do produtor (cabeçalho)
   * @param estado        UF do produtor (cabeçalho)
   * @return bytes do PDF gerado
   * @throws RelatorioGenerationException se a compilação/preenchimento/exportação falhar
   */
  public byte[] gerarPdf(
      RelatorioConformidadeResponse relatorio, String nomeProdutor, String estado) {
    try {
      Map<String, Object> params = montarParametros(relatorio, nomeProdutor, estado);
      JasperPrint print =
          JasperFillManager.fillReport(getReport(), params, new JREmptyDataSource(1));
      byte[] pdf = JasperExportManager.exportReportToPdf(print);
      log.info("Relatorio de conformidade PDF gerado: periodo={} bytes={}",
          relatorio.periodo(), pdf.length);
      return pdf;
    } catch (RelatorioGenerationException e) {
      throw e;
    } catch (Exception e) {
      // Detalhe técnico só no log; ao cliente, mensagem genérica via RelatorioGenerationException.
      log.error("Falha ao gerar o PDF do relatorio de conformidade do periodo {}",
          relatorio.periodo(), e);
      throw new RelatorioGenerationException(
          "Falha ao gerar o PDF do relatorio de conformidade do periodo "
              + relatorio.periodo(), e);
    }
  }

  /** Compila o template uma única vez e o mantém em cache. */
  private JasperReport getReport() {
    JasperReport report = compiledReport;
    if (report == null) {
      synchronized (this) {
        report = compiledReport;
        if (report == null) {
          try (InputStream is = new ClassPathResource(TEMPLATE).getInputStream()) {
            report = JasperCompileManager.compileReport(is);
            compiledReport = report;
          } catch (Exception e) {
            throw new RelatorioGenerationException(
                "Falha ao compilar o template do relatorio (" + TEMPLATE + ")", e);
          }
        }
      }
    }
    return report;
  }

  /** Monta os parâmetros escalares do relatório. */
  private Map<String, Object> montarParametros(
      RelatorioConformidadeResponse r, String nomeProdutor, String estado) {
    Map<String, Object> params = new HashMap<>();
    params.put("PERIODO", r.periodo());
    params.put("PRODUTOR_NOME", nomeProdutor != null ? nomeProdutor : "-");
    params.put("PRODUTOR_ESTADO", estado != null ? estado : "-");

    params.put("DEBITO_IBS", moeda(r.debitoIbs()));
    params.put("DEBITO_CBS", moeda(r.debitoCbs()));
    params.put("DEBITO_TOTAL", moeda(r.debitoTotal()));
    params.put("QTD_SAIDAS", String.valueOf(r.quantidadeSaidas()));

    params.put("CREDITO_IBS", moeda(r.creditoIbs()));
    params.put("CREDITO_CBS", moeda(r.creditoCbs()));
    params.put("CREDITO_TOTAL", moeda(r.creditoTotal()));
    params.put("QTD_ENTRADAS", String.valueOf(r.quantidadeEntradas()));

    params.put("SALDO_IBS", moeda(r.saldoIbs()));
    params.put("SALDO_CBS", moeda(r.saldoCbs()));
    params.put("SALDO_A_PAGAR", moeda(r.saldoAPagar()));
    params.put("SALDO_LABEL",
        r.saldoAPagar().signum() < 0 ? "SALDO CREDOR A TRANSPORTAR (R$):" : "SALDO A PAGAR (R$):");
    return params;
  }

  /** Formata um valor monetário em pt-BR com 2 casas; nulo vira "-". */
  private String moeda(BigDecimal valor) {
    if (valor == null) {
      return "-";
    }
    NumberFormat nf = NumberFormat.getNumberInstance(BR);
    nf.setMinimumFractionDigits(2);
    nf.setMaximumFractionDigits(2);
    return nf.format(valor);
  }
}
