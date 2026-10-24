package br.com.agrocontabil.danfe;

import br.com.agrocontabil.exception.DanfeGenerationException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
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
 * Gera o PDF da DANFE (Documento Auxiliar da NF-e) a partir de uma {@link NotaFiscal}
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

  /** Marca d'água do preview de rascunho (issue #89), parâmetro {@code MARCA_DAGUA} do template. */
  public static final String MARCA_DAGUA_RASCUNHO = "RASCUNHO — SEM VALOR FISCAL";
  private static final Locale BR = Locale.of("pt", "BR");
  private static final DateTimeFormatter DATA_FMT =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  /** Cache do template compilado (double-checked locking). */
  private volatile JasperReport compiledReport;

  /**
   * Gera o PDF da DANFE para a nota informada.
   *
   * <p>Deve ser chamado dentro de uma transação ativa: acessa a coleção de itens (lazy)
   * da nota.
   *
   * @param nota nota fiscal com produtor, itens e totais carregados
   * @return bytes do PDF gerado
   * @throws DanfeGenerationException se a compilação/preenchimento/exportação falhar
   */
  public byte[] gerarPdf(NotaFiscal nota) {
    return gerar(nota, montarParametros(nota));
  }

  /**
   * Gera a pré-visualização da DANFE de uma nota ainda não emitida (issue #89).
   *
   * <p>Mesmo layout da DANFE, com a marca d'água {@link #MARCA_DAGUA_RASCUNHO} em todas as páginas
   * e a chave de acesso em branco — mesmo que a nota carregue uma, o preview nunca a mostra: o
   * documento não tem valor fiscal e não pode ser confundido com a DANFE de uma NF-e autorizada.
   * Não valida, não assina e não altera a nota.
   *
   * @param nota nota com produtor e itens carregados
   * @return bytes do PDF gerado
   * @throws DanfeGenerationException se a compilação/preenchimento/exportação falhar
   */
  public byte[] gerarPreview(NotaFiscal nota) {
    Map<String, Object> params = montarParametros(nota);
    params.put("CHAVE_ACESSO", "");
    params.put("MARCA_DAGUA", MARCA_DAGUA_RASCUNHO);
    return gerar(nota, params);
  }

  private byte[] gerar(NotaFiscal nota, Map<String, Object> params) {
    try {
      List<DanfeItemRow> itens = montarItens(nota);
      JasperPrint print =
          JasperFillManager.fillReport(getReport(), params, new JRBeanCollectionDataSource(itens));
      byte[] pdf = JasperExportManager.exportReportToPdf(print);
      log.info("DANFE gerada: notaId={} itens={} bytes={}",
          nota.getId(), itens.size(), pdf.length);
      return pdf;
    } catch (DanfeGenerationException e) {
      throw e;
    } catch (Exception e) {
      // Detalhe técnico só no log; ao cliente, mensagem genérica via DanfeGenerationException.
      log.error("Falha ao gerar a DANFE da nota {}", nota.getId(), e);
      throw new DanfeGenerationException(
          "Falha ao gerar o PDF da DANFE para a nota " + nota.getId(), e);
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

  /** Monta os parâmetros escalares do relatório a partir da nota. */
  private Map<String, Object> montarParametros(NotaFiscal nota) {
    Map<String, Object> params = new HashMap<>();
    params.put("TITULO", "DANFE");
    params.put("NUMERO", nota.getNumero() != null ? String.valueOf(nota.getNumero()) : "");
    params.put("SERIE", nota.getSerie() != null ? String.valueOf(nota.getSerie()) : "");
    params.put("MODELO", nota.getModelo());
    params.put("NAT_OPERACAO", nota.getNaturezaOperacao());
    params.put("CHAVE_ACESSO", nota.getChaveAcesso());
    params.put("DATA_EMISSAO", formatarData(nota.getDataEmissao()));
    params.put("STATUS", nota.getStatus() != null ? nota.getStatus().name() : "");

    Produtor emit = nota.getProdutor();
    params.put("EMIT_NOME", emit != null ? emit.getNomeCompleto() : "");
    params.put("EMIT_CPF_CNPJ", emit != null ? emit.getCpfCnpj() : "");
    params.put("EMIT_ESTADO", emit != null ? emit.getEstado() : "");

    params.put("DEST_NOME", nota.getDestinatarioNome());
    params.put("DEST_CPF_CNPJ", nota.getDestinatarioCpfCnpj());
    params.put("DEST_ENDERECO", formatarEndereco(nota.getDestinatarioEndereco()));

    params.put("TOTAL_PRODUTOS", moeda(nota.getValorTotalProdutos()));
    params.put("TOTAL_IBS", moeda(nota.getValorTotalIbs()));
    params.put("TOTAL_CBS", moeda(nota.getValorTotalCbs()));
    params.put("TOTAL_NOTA", moeda(nota.getValorTotalNota()));
    return params;
  }

  /** Converte os itens da nota em linhas formatadas para o datasource. */
  private List<DanfeItemRow> montarItens(NotaFiscal nota) {
    List<DanfeItemRow> rows = new ArrayList<>();
    for (NotaFiscalItem item : nota.getItens()) {
      rows.add(new DanfeItemRow(
          item.getNumeroItem(),
          item.getDescricao(),
          item.getUnidade(),
          quantidade(item.getQuantidade()),
          moeda(item.getValorUnitario()),
          moeda(item.getValorTotal()),
          moeda(item.getValorIbs()),
          moeda(item.getValorCbs())));
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

  /** Formata uma quantidade em pt-BR com até 4 casas decimais; nulo vira "-". */
  private String quantidade(BigDecimal valor) {
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
  private String formatarEndereco(EnderecoDestinatario endereco) {
    if (endereco == null) {
      return "-";
    }
    List<String> partes = new ArrayList<>();
    if (endereco.getLogradouro() != null && !endereco.getLogradouro().isBlank()) {
      String logradouro = endereco.getLogradouro();
      if (endereco.getNumero() != null && !endereco.getNumero().isBlank()) {
        logradouro = logradouro + ", " + endereco.getNumero();
      }
      partes.add(logradouro);
    }
    if (endereco.getMunicipio() != null && !endereco.getMunicipio().isBlank()) {
      String municipio = endereco.getMunicipio();
      if (endereco.getUf() != null && !endereco.getUf().isBlank()) {
        municipio = municipio + "/" + endereco.getUf();
      }
      partes.add(municipio);
    }
    if (endereco.getCep() != null && !endereco.getCep().isBlank()) {
      partes.add("CEP " + endereco.getCep());
    }
    return partes.isEmpty() ? "-" : String.join(" - ", partes);
  }
}
