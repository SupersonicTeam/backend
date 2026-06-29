package br.com.agrocontabil.danfe;

import br.com.agrocontabil.exception.DanfeGenerationException;
import br.com.agrocontabil.model.RecipientAddress;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Gera o PDF da DANFE (Documento Auxiliar da NF-e) a partir de uma {@link Invoice}
 * (issue #14 — RF011), usando JasperReports.
 *
 * <p>O template {@code reports/danfe.jrxml} é compilado uma única vez (cache do
 * {@link JasperReport}) e reutilizado a cada geração. Toda a formatação de valores
 * (datas, moeda, quantidades) é centralizada aqui — o template apenas exibe as Strings.
 *
 * <p><strong>Escopo:</strong> DANFE simplificada e informativa para o TCC; não substitui a
 * DANFE oficial da SEFAZ.
 */
@Service
public class DanfePdfService {

  private static final Logger log = LoggerFactory.getLogger(DanfePdfService.class);

  private static final String TEMPLATE = "reports/danfe.jrxml";
  private static final Locale BR = Locale.of("pt", "BR");
  private static final DateTimeFormatter DATA_FMT =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  /** Cache do template compilado (double-checked locking). */
  private volatile JasperReport compiledReport;

  /**
   * Gera o PDF da DANFE para a invoice informada.
   *
   * <p>Deve ser chamado dentro de uma transação ativa: acessa a coleção de items (lazy)
   * da invoice.
   *
   * @param invoice invoice fiscal com producer, items e totais carregados
   * @return bytes do PDF gerado
   * @throws DanfeGenerationException se a compilação/preenchimento/exportação falhar
   */
  public byte[] generatePdf(Invoice invoice) {
    try {
      Map<String, Object> params = buildParameters(invoice);
      List<DanfeItemRow> items = buildItems(invoice);
      JasperPrint print =
          JasperFillManager.fillReport(getReport(), params, new JRBeanCollectionDataSource(items));
      byte[] pdf = JasperExportManager.exportReportToPdf(print);
      log.info("DANFE gerada: notaId={} itens={} bytes={}",
          invoice.getId(), items.size(), pdf.length);
      return pdf;
    } catch (DanfeGenerationException e) {
      throw e;
    } catch (Exception e) {
      // Detalhe técnico só no log; ao cliente, mensagem genérica via DanfeGenerationException.
      log.error("Falha ao gerar a DANFE da nota {}", invoice.getId(), e);
      throw new DanfeGenerationException(
          "Falha ao gerar o PDF da DANFE para a nota " + invoice.getId(), e);
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
            throw new DanfeGenerationException(
                "Falha ao compilar o template da DANFE (" + TEMPLATE + ")", e);
          }
        }
      }
    }
    return report;
  }

  /** Monta os parâmetros escalares do relatório a partir da invoice. */
  private Map<String, Object> buildParameters(Invoice invoice) {
    Map<String, Object> params = new HashMap<>();
    params.put("TITULO", "DANFE");
    params.put("NUMERO", invoice.getNumber() != null ? String.valueOf(invoice.getNumber()) : "");
    params.put("SERIE", invoice.getSeries() != null ? String.valueOf(invoice.getSeries()) : "");
    params.put("MODELO", invoice.getModel());
    params.put("NAT_OPERACAO", invoice.getOperationNature());
    params.put("CHAVE_ACESSO", invoice.getAccessKey());
    params.put("DATA_EMISSAO", formatarData(invoice.getIssuanceDate()));
    params.put("STATUS", invoice.getStatus() != null ? invoice.getStatus().name() : "");

    Producer emit = invoice.getProducer();
    params.put("EMIT_NOME", emit != null ? emit.getFullName() : "");
    params.put("EMIT_CPF_CNPJ", emit != null ? emit.getTaxId() : "");
    params.put("EMIT_ESTADO", emit != null ? emit.getState() : "");

    params.put("DEST_NOME", invoice.getRecipientName());
    params.put("DEST_CPF_CNPJ", invoice.getRecipientTaxId());
    params.put("DEST_ENDERECO", formatarEndereco(invoice.getRecipientAddress()));

    params.put("TOTAL_PRODUTOS", moeda(invoice.getTotalProductsValue()));
    params.put("TOTAL_IBS", moeda(invoice.getTotalIbsValue()));
    params.put("TOTAL_CBS", moeda(invoice.getTotalCbsValue()));
    params.put("TOTAL_NOTA", moeda(invoice.getTotalInvoiceValue()));
    return params;
  }

  /** Converte os items da invoice em linhas formatadas para o datasource. */
  private List<DanfeItemRow> buildItems(Invoice invoice) {
    List<DanfeItemRow> rows = new ArrayList<>();
    for (InvoiceItem item : invoice.getItems()) {
      rows.add(new DanfeItemRow(
          item.getItemNumber(),
          item.getDescription(),
          item.getUnit(),
          quantity(item.getQuantity()),
          moeda(item.getUnitPrice()),
          moeda(item.getTotalValue()),
          moeda(item.getIbsValue()),
          moeda(item.getCbsValue())));
    }
    return rows;
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

  /** Formata uma quantity em pt-BR com até 4 casas decimais; nulo vira "-". */
  private String quantity(BigDecimal valor) {
    if (valor == null) {
      return "-";
    }
    NumberFormat nf = NumberFormat.getNumberInstance(BR);
    nf.setMinimumFractionDigits(0);
    nf.setMaximumFractionDigits(4);
    return nf.format(valor);
  }

  /** Formata a data de emissão; nula vira string vazia. */
  private String formatarData(OffsetDateTime data) {
    return data != null ? data.format(DATA_FMT) : "";
  }

  /** Compõe o endereço do destinatário numa única linha; nulo/vazio vira "-". */
  private String formatarEndereco(RecipientAddress endereco) {
    if (endereco == null) {
      return "-";
    }
    List<String> partes = new ArrayList<>();
    if (endereco.getStreet() != null && !endereco.getStreet().isBlank()) {
      String street = endereco.getStreet();
      if (endereco.getNumber() != null && !endereco.getNumber().isBlank()) {
        street = street + ", " + endereco.getNumber();
      }
      partes.add(street);
    }
    if (endereco.getCity() != null && !endereco.getCity().isBlank()) {
      String city = endereco.getCity();
      if (endereco.getUf() != null && !endereco.getUf().isBlank()) {
        city = city + "/" + endereco.getUf();
      }
      partes.add(city);
    }
    if (endereco.getZipCode() != null && !endereco.getZipCode().isBlank()) {
      partes.add("CEP " + endereco.getZipCode());
    }
    return partes.isEmpty() ? "-" : String.join(" - ", partes);
  }
}
