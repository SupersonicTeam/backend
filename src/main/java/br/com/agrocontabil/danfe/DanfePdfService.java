package br.com.agrocontabil.danfe;

import br.com.agrocontabil.exception.DanfeGenerationException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.EnderecoProdutor;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
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
import java.util.function.Function;
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

  /** Valor impresso nos totais que o XML leva zerados (ICMS/IPI, frete, seguro etc.). */
  private static final String ZERO_IMPRESSO = "0,00";

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
    params.put("CHAVE_ACESSO", "—");
    params.put("CONSULTA_CHAVE", "A chave de acesso é gerada na emissão do XML.");
    params.put("PROTOCOLO", "Não autorizada (pré-visualização)");
    params.put("INFO_COMPLEMENTAR", INFO_PREVIEW + INFO_REFORMA);
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
    params.put("LOGO", LOGO);
    params.put("NUMERO", numeroDocumento(nota.getNumero()));
    params.put("SERIE", nota.getSerie() != null ? String.valueOf(nota.getSerie()) : "");
    params.put("MODELO", nota.getModelo());
    params.put("TIPO_OPERACAO", tipoOperacao(nota));
    params.put("TIPO_DOCUMENTO",
        nota.getTipoDocumento() == TipoDocumentoFiscal.NFA ? "NFA (nota avulsa)" : "NF-e");
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
    params.put("SITUACAO", nota.getStatus() != null
        ? nota.getStatus().name().replace('_', ' ') : "");
    params.put("ORIGEM", nota.getOrigem() == OrigemNotaFiscal.IMPORTADA
        ? "Importada (nota de terceiro)" : "Emitida pelo produtor");

    montarEmitente(nota, params);

    params.put("DEST_NOME", nota.getDestinatarioNome());
    params.put("DEST_CPF_CNPJ", documento(nota.getDestinatarioCpfCnpj()));
    params.put("DEST_IE", nota.getDestinatarioIe());
    EnderecoDestinatario dest = nota.getDestinatarioEndereco();
    if (dest != null) {
      params.put("DEST_ENDERECO", juntar(", ", dest.getLogradouro(), dest.getNumero()));
      // O que vai no xBairro do XML: o domínio não modela o bairro do destinatário (#88).
      params.put("DEST_BAIRRO", NotaFiscalNfeMapper.BAIRRO_DESTINATARIO);
      params.put("DEST_MUNICIPIO", dest.getMunicipio());
      params.put("DEST_UF", dest.getUf());
      params.put("DEST_CEP", cep(dest.getCep()));
      params.put("DEST_IBGE", dest.getCodigoMunicipioIbge());
    }

    params.put("TOTAL_PRODUTOS", moeda(nota.getValorTotalProdutos()));
    params.put("TOTAL_NOTA", moeda(nota.getValorTotalNota()));
    // Total do ICMS/IPI, frete, seguro, desconto e outras: o XML leva zero (o produtor trata do
    // ICMS por guia, fora da NF-e; ver docs/premissas-e-escopo.md). A DANFE imprime o mesmo.
    params.put("ZERO", ZERO_IMPRESSO);
    // Total do IBS/CBS/IS (NT 2026.010, item 4.1): os mesmos do IBSCBSTot do XML.
    params.put("TOTAL_CBS", moeda(nota.getValorTotalCbs()));
    params.put("TOTAL_IBS_UF", moeda(somaParcelas(nota, NotaFiscalNfeMapper.ParcelasIbs::valorUf)));
    params.put("TOTAL_IBS_MUN",
        moeda(somaParcelas(nota, NotaFiscalNfeMapper.ParcelasIbs::valorMun)));
    params.put("INFO_COMPLEMENTAR", INFO_REFORMA);
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
    params.put("EMIT_CRT", crtImpresso(NotaFiscalNfeMapper.crt(emit.getRegimeTributario())));
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
   * Converte os itens da nota em linhas do quadro de produtos (NT 2026.010, item 4.3), com os
   * mesmos valores do XML: CST e cClassTrib do tratamento aplicado, {@code vBC} cheio, parcelas
   * do IBS e a alíquota efetiva quando há {@code gRed} — tudo pelos helpers do
   * {@link NotaFiscalNfeMapper}, nunca recalculado aqui.
   */
  private List<DanfeItemRow> montarItens(NotaFiscal nota) {
    List<DanfeItemRow> rows = new ArrayList<>();
    for (NotaFiscalItem item : nota.getItens()) {
      String cClassTrib = NotaFiscalNfeMapper.cClassTrib(item);
      String cst = NotaFiscalNfeMapper.cst(item);
      boolean comIbsCbs = item.getAliquotaIbs() != null && item.getAliquotaCbs() != null;
      NotaFiscalNfeMapper.ParcelasIbs parcelas = NotaFiscalNfeMapper.parcelasIbs(item);
      rows.add(new DanfeItemRow(
          juntar("\n", juntar(" - ", item.getCodigoProduto(), item.getDescricao()), juntar(" ",
              item.getNcm() != null ? "[NCM " + ncm(item.getNcm()) + "]" : null,
              cClassTrib != null ? "[cClassTrib " + cClassTrib + "]" : null)),
          juntar("\n", cst != null ? "CST " + cst : null,
              item.getCfop() != null ? "CFOP " + item.getCfop() : null),
          juntar("\n", quantidade(item.getQuantidade()), item.getUnidade()),
          moeda(item.getValorUnitario()),
          moeda(item.getValorTotal()),
          comIbsCbs ? moeda(item.getValorTotal()) : null,
          comIbsCbs ? String.join("\n",
              "CBS " + percentual(NotaFiscalNfeMapper.aliquotaEfetiva(item.getAliquotaCbs(), item)),
              "IBS UF "
                  + percentual(NotaFiscalNfeMapper.aliquotaEfetiva(parcelas.aliquotaUf(), item)),
              "IBS MUN "
                  + percentual(NotaFiscalNfeMapper.aliquotaEfetiva(parcelas.aliquotaMun(), item)))
              : null,
          comIbsCbs ? String.join("\n",
              "CBS " + moeda(item.getValorCbs()),
              "IBS UF " + moeda(parcelas.valorUf()),
              "IBS MUN " + moeda(parcelas.valorMun()))
              : null));
    }
    return rows;
  }

  /** Soma de uma parcela do IBS nos itens com IBS calculado; nula se nenhum tem. */
  private static BigDecimal somaParcelas(NotaFiscal nota,
      Function<NotaFiscalNfeMapper.ParcelasIbs, BigDecimal> parcela) {
    return nota.getItens().stream()
        .filter(i -> i.getValorIbs() != null)
        .map(i -> parcela.apply(NotaFiscalNfeMapper.parcelasIbs(i)))
        .reduce(BigDecimal::add).orElse(null);
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

  /** Alíquota em proporção decimal (0.0090) como percentual ("0,90%"); nula vira "-". */
  private String percentual(BigDecimal aliquota) {
    if (aliquota == null) {
      return "-";
    }
    NumberFormat nf = NumberFormat.getNumberInstance(BR);
    nf.setMinimumFractionDigits(2);
    nf.setMaximumFractionDigits(4);
    return nf.format(aliquota.movePointRight(2)) + "%";
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
