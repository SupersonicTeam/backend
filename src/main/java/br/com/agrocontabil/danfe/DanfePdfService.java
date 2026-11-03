package br.com.agrocontabil.danfe;

import br.com.agrocontabil.exception.DanfeGenerationException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
  private static final DateTimeFormatter DIA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter HORA_FMT = DateTimeFormatter.ofPattern("HH:mm");
  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

  /** Marca d'água da DANFE de NF-e cancelada: o PDF não pode passar por documento válido. */
  public static final String MARCA_DAGUA_CANCELADA = "NF-e CANCELADA";

  /** Marca do AgroContábil no cabeçalho (classpath; o JasperReports carrega pelo nome). */
  private static final String LOGO = "reports/agrocontabil-logo.png";

  /** Base legal do bloco de imposto, nas informações complementares. */
  private static final String INFO_REFORMA = "Tributos da reforma tributária do consumo:"
      + " IBS e CBS calculados conforme a EC 132/2023 e a LC 214/2025.";

  /** Aviso do preview nas informações complementares (issue #89). */
  private static final String INFO_PREVIEW = "PRÉ-VISUALIZAÇÃO SEM VALOR FISCAL: nota ainda não"
      + " emitida. Chave de acesso, protocolo e assinatura só existem depois da geração do XML e"
      + " da autorização na SEFAZ. ";

  /** Cache do template compilado (double-checked locking). */
  private volatile JasperReport compiledReport;

  /**
   * Gera o PDF da DANFE para a nota informada.
   *
   * <p>A nota precisa chegar com itens e produtor já carregados (o {@code NotaFiscalService}
   * usa o {@code JOIN FETCH} da emissão, #99): a geração roda fora de transação.
   *
   * @param nota nota fiscal com produtor, itens e totais carregados
   * @return bytes do PDF gerado
   * @throws DanfeGenerationException se a compilação/preenchimento/exportação falhar
   */
  public byte[] gerarPdf(NotaFiscal nota) {
    Map<String, Object> params = montarParametros(nota);
    if (nota.getStatus() == StatusNotaFiscal.CANCELADA) {
      params.put("MARCA_DAGUA", MARCA_DAGUA_CANCELADA);
    }
    // Com o XML (o assinado, ou o do terceiro na importada), os valores fiscais saem dele: é o
    // documento fiscal. Sem XML, da nota, pelos mesmos helpers que montarão o XML.
    return gerar(nota, params, () -> nota.getXmlGerado() != null
        ? DanfeValores.doXml(nota.getXmlGerado()) : DanfeValores.daNota(nota));
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
    params.put("CHAVE_ACESSO", "—");
    params.put("CONSULTA_CHAVE", "A chave de acesso é gerada na emissão do XML.");
    params.put("PROTOCOLO", "Não autorizada (pré-visualização)");
    params.put("INFO_COMPLEMENTAR", INFO_PREVIEW + params.get("INFO_COMPLEMENTAR"));
    params.put("MARCA_DAGUA", MARCA_DAGUA_RASCUNHO);
    // O preview mostra a nota como está: um XML antigo não vale para ela.
    return gerar(nota, params, () -> DanfeValores.daNota(nota));
  }

  private byte[] gerar(NotaFiscal nota, Map<String, Object> params,
      Supplier<DanfeValores> fonte) {
    try {
      DanfeValores valores = fonte.get();
      montarValores(valores, params);
      List<DanfeItemRow> itens = montarItens(valores);
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
    params.put("LOGO", LOGO);
    params.put("NUMERO", numeroDocumento(nota.getNumero()));
    params.put("SERIE", nota.getSerie() != null ? String.valueOf(nota.getSerie()) : "");
    params.put("MODELO", nota.getModelo());
    params.put("TIPO_OPERACAO", tipoOperacao(nota));
    params.put("NAT_OPERACAO", nota.getNaturezaOperacao());
    params.put("CHAVE_ACESSO", chave(nota.getChaveAcesso()));
    params.put("CONSULTA_CHAVE", nota.getChaveAcesso() != null
        ? "Consulta de autenticidade no portal nacional da NF-e (www.nfe.fazenda.gov.br/portal)"
            + " ou no site da SEFAZ autorizadora."
        : "A chave de acesso é gerada na emissão do XML.");
    params.put("PROTOCOLO", protocolo(nota));
    params.put("DATA_EMISSAO", formatarDia(nota.getDataEmissao()));
    params.put("DATA_SAIDA", formatarDia(nota.getDataSaida()));
    params.put("HORA_SAIDA", formatarHora(nota.getDataSaida()));
    montarEmitente(nota, params);

    params.put("DEST_NOME", nota.getDestinatarioNome());
    params.put("DEST_CPF_CNPJ", documento(nota.getDestinatarioCpfCnpj()));
    params.put("DEST_IE", nota.getDestinatarioIe());
    EnderecoDestinatario dest = nota.getDestinatarioEndereco();
    if (dest != null) {
      params.put("DEST_ENDERECO", juntar(", ", dest.getLogradouro(), dest.getNumero()));
      params.put("DEST_MUNICIPIO", dest.getMunicipio());
      params.put("DEST_UF", dest.getUf());
      params.put("DEST_CEP", cep(dest.getCep()));
    }
    params.put("INFO_COMPLEMENTAR", situacao(nota) + " " + INFO_REFORMA);
    params.put("GERADO_EM", ZonedDateTime.now(BRASILIA).format(DATA_FMT));
    return params;
  }

  /**
   * Emitente: o produtor nas notas próprias; o terceiro (só nome e documento, do XML importado) nas
   * notas de entrada importadas.
   */
  private void montarEmitente(NotaFiscal nota, Map<String, Object> params) {
    if (nota.getOrigem() == OrigemNotaFiscal.IMPORTADA) {
      params.put("EMIT_NOME", nota.getEmitenteNome());
      params.put("EMIT_CPF_CNPJ", documento(nota.getEmitenteCpfCnpj()));
      return;
    }
    Produtor emit = nota.getProdutor();
    if (emit == null) {
      return;
    }
    params.put("EMIT_NOME", emit.getNomeCompleto());
    params.put("EMIT_CPF_CNPJ", documento(emit.getCpfCnpj()));
    params.put("EMIT_IE", emit.getInscricaoEstadual());
    params.put("PROPRIA", "S");
    EnderecoProdutor end = emit.getEndereco();
    if (end != null) {
      params.put("EMIT_ENDERECO", juntar(" - ",
          juntar(", ", end.getLogradouro(), end.getNumero()), end.getComplemento()));
      params.put("EMIT_BAIRRO_CEP", juntar(" - ", end.getBairro(),
          end.getCep() != null ? "CEP " + cep(end.getCep()) : null));
      params.put("EMIT_MUNICIPIO_UF", juntar(" - ", end.getMunicipio(), emit.getEstado()));
    } else {
      params.put("EMIT_MUNICIPIO_UF", emit.getEstado());
    }
  }

  /**
   * Valores fiscais do cabeçalho e dos totais: CRT (item 4.2), bairro do destinatário, quadro do
   * ICMS/IPI e totais da nota e o bloco TOTAL DO IBS / CBS / IS (item 4.1). O IS não está no XML:
   * o campo fica vazio.
   */
  private void montarValores(DanfeValores v, Map<String, Object> params) {
    params.put("EMIT_CRT", v.crt() != null ? crtImpresso(v.crt()) : null);
    params.put("DEST_BAIRRO", v.bairroDestinatario());
    DanfeValores.Totais t = v.totais();
    params.put("BASE_ICMS", moeda(t.vBC()));
    params.put("VALOR_ICMS", moeda(t.vICMS()));
    params.put("BASE_ICMS_ST", moeda(t.vBCST()));
    params.put("VALOR_ICMS_ST", moeda(t.vST()));
    params.put("VALOR_IPI", moeda(t.vIPI()));
    params.put("TOTAL_PRODUTOS", moeda(t.vProd()));
    params.put("VALOR_FRETE", moeda(t.vFrete()));
    params.put("VALOR_SEGURO", moeda(t.vSeg()));
    params.put("VALOR_DESCONTO", moeda(t.vDesc()));
    params.put("VALOR_OUTRAS", moeda(t.vOutro()));
    params.put("TOTAL_NOTA", moeda(t.vNF()));
    params.put("TOTAL_CBS", moeda(v.totalCbs()));
    params.put("TOTAL_IBS_UF", moeda(v.totalIbsUf()));
    params.put("TOTAL_IBS_MUN", moeda(v.totalIbsMun()));
    // Funrural/Senar retido pelo adquirente (issue #332): o mesmo texto do infCpl, só na nota
    // própria (na importada o comprador é o produtor; o texto do terceiro não é nosso).
    if (v.funrural() != null && "S".equals(params.get("PROPRIA"))) {
      params.put("INFO_COMPLEMENTAR", params.get("INFO_COMPLEMENTAR") + " " + v.funrural());
    }
  }

  /**
   * Linhas do quadro de produtos (NT 2026.010, item 4.3): código e descrição com
   * {@code [NCM][cClassTrib]}, CST/CFOP, quantidade/unidade, valores, base do IBS/CBS e, por esfera
   * (CBS, IBS UF, IBS Município), a alíquota impressa (efetiva com {@code gRed}) e o valor.
   */
  private List<DanfeItemRow> montarItens(DanfeValores valores) {
    List<DanfeItemRow> rows = new ArrayList<>();
    for (DanfeValores.Item item : valores.itens()) {
      boolean g = item.ibsCbs();
      rows.add(new DanfeItemRow(
          juntar("\n", juntar(" - ", item.codigo(), item.descricao()), juntar(" ",
              item.ncm() != null ? "[NCM " + ncm(item.ncm()) + "]" : null,
              item.cClassTrib() != null ? "[cClassTrib " + item.cClassTrib() + "]" : null)),
          juntar("\n", item.cst() != null ? "CST " + item.cst() : null,
              item.cfop() != null ? "CFOP " + item.cfop() : null),
          juntar("\n", quantidade(item.quantidade()), item.unidade()),
          moeda(item.valorUnitario()),
          moeda(item.valorTotal()),
          g ? moeda(item.base()) : null,
          g ? String.join("\n",
              "CBS " + percentual(item.aliquotaCbs()),
              "IBS UF " + percentual(item.aliquotaIbsUf()),
              "IBS MUN " + percentual(item.aliquotaIbsMun())) : null,
          g ? String.join("\n",
              "CBS " + moeda(item.valorCbs()),
              "IBS UF " + moeda(item.valorIbsUf()),
              "IBS MUN " + moeda(item.valorIbsMun())) : null));
    }
    return rows;
  }

  /** Documento, situação e origem, no início das informações complementares. */
  private static String situacao(NotaFiscal nota) {
    return "Documento: "
        + (nota.getTipoDocumento() == TipoDocumentoFiscal.NFA ? "NFA (nota avulsa)" : "NF-e")
        + ". Situação: " + (nota.getStatus() != null
            ? nota.getStatus().name().replace('_', ' ') : "-")
        + ". Origem: " + (nota.getOrigem() == OrigemNotaFiscal.IMPORTADA
            ? "importada (nota de terceiro)" : "emitida pelo produtor") + ".";
  }

  /** CRT com a descrição do MOC ({@code 3 - REGIME NORMAL}). */
  private static String crtImpresso(String crt) {
    return switch (crt) {
      case "1" -> "1 - SIMPLES NACIONAL";
      case "2" -> "2 - SIMPLES NACIONAL - EXCESSO DE SUBLIMITE";
      case "4" -> "4 - MEI";
      default -> crt + " - REGIME NORMAL";
    };
  }

  /** "1 - SAÍDA" ou "0 - ENTRADA", pelo CFOP do primeiro item (1/2/3 = entrada). */
  private static String tipoOperacao(NotaFiscal nota) {
    String cfop = nota.getItens().isEmpty() ? null : nota.getItens().get(0).getCfop();
    boolean entrada = cfop != null && !cfop.isEmpty() && "123".indexOf(cfop.charAt(0)) >= 0;
    return entrada ? "0 - ENTRADA" : "1 - SAÍDA";
  }

  /** Protocolo e data da autorização; sem autorização, o aviso. */
  private String protocolo(NotaFiscal nota) {
    if (nota.getProtocoloAutorizacao() == null) {
      return "Não autorizada";
    }
    return nota.getDataAutorizacao() == null ? nota.getProtocoloAutorizacao()
        : nota.getProtocoloAutorizacao() + " - " + formatarData(nota.getDataAutorizacao());
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

  /** Alíquota em percentual (0.9000) como impressa ("0,90%"); nula vira "-". */
  private String percentual(BigDecimal aliquota) {
    if (aliquota == null) {
      return "-";
    }
    NumberFormat nf = NumberFormat.getNumberInstance(BR);
    nf.setMinimumFractionDigits(2);
    nf.setMaximumFractionDigits(4);
    return nf.format(aliquota) + "%";
  }

  /** Data e hora em Brasília; nula vira string vazia. */
  private String formatarData(OffsetDateTime data) {
    return data != null ? data.atZoneSameInstant(BRASILIA).format(DATA_FMT) : "";
  }

  /** Só a data em Brasília (campo "Data de emissão" do quadro do destinatário). */
  private String formatarDia(OffsetDateTime data) {
    return data != null ? data.atZoneSameInstant(BRASILIA).format(DIA_FMT) : "";
  }

  /** Só a hora em Brasília (campo "Hora de saída"). */
  private String formatarHora(OffsetDateTime data) {
    return data != null ? data.atZoneSameInstant(BRASILIA).format(HORA_FMT) : "";
  }

  /** CPF (11 dígitos) ou CNPJ (14) com máscara; outro formato sai como veio. */
  static String documento(String valor) {
    if (valor == null) {
      return null;
    }
    String d = valor.replaceAll("[^0-9]", "");
    if (d.length() == 11) {
      return d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6, 9) + "-"
          + d.substring(9);
    }
    if (d.length() == 14) {
      return d.substring(0, 2) + "." + d.substring(2, 5) + "." + d.substring(5, 8) + "/"
          + d.substring(8, 12) + "-" + d.substring(12);
    }
    return valor;
  }

  /** CEP com hífen ({@code 00000-000}); outro formato sai como veio. */
  static String cep(String valor) {
    if (valor == null) {
      return null;
    }
    String d = valor.replaceAll("[^0-9]", "");
    return d.length() == 8 ? d.substring(0, 5) + "-" + d.substring(5) : valor;
  }

  /** NCM com pontos ({@code 0000.00.00}); outro formato sai como veio. */
  static String ncm(String valor) {
    if (valor == null || !valor.matches("[0-9]{8}")) {
      return valor;
    }
    return valor.substring(0, 4) + "." + valor.substring(4, 6) + "." + valor.substring(6);
  }

  /** Chave de acesso em blocos de 4 dígitos, como na DANFE oficial. */
  static String chave(String valor) {
    if (valor == null) {
      return null;
    }
    StringBuilder blocos = new StringBuilder();
    for (int i = 0; i < valor.length(); i += 4) {
      if (i > 0) {
        blocos.append(' ');
      }
      blocos.append(valor, i, Math.min(i + 4, valor.length()));
    }
    return blocos.toString();
  }

  /** Número do documento com 9 dígitos agrupados ({@code 000.000.001}). */
  static String numeroDocumento(Long numero) {
    if (numero == null) {
      return "";
    }
    String d = String.format("%09d", numero);
    return d.length() == 9
        ? d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6) : d;
  }

  /** Junta as partes não vazias com o separador; tudo vazio vira nulo. */
  private static String juntar(String separador, String... partes) {
    String junto = Stream.of(partes).filter(p -> p != null && !p.isBlank())
        .collect(Collectors.joining(separador));
    return junto.isEmpty() ? null : junto;
  }
}
