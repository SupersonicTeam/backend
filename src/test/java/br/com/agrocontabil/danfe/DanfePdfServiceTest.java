package br.com.agrocontabil.danfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.exception.DanfeGenerationException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.nfe.NotaFiscalNfeMapper;
import br.com.agrocontabil.nfe.XmlGenerator;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Teste de integração do {@link DanfePdfService}.
 *
 * <p>Exercita o pipeline completo: compilação real do template {@code danfe.jrxml},
 * preenchimento com uma nota de exemplo e exportação para PDF. Valida que a saída é um
 * documento PDF não vazio (assinatura {@code %PDF}).
 */
class DanfePdfServiceTest {

  private static final String CHAVE = "41262612345678000195550010000000011000000012";

  /** Chave como a DANFE a imprime: blocos de 4 dígitos. */
  private static final String CHAVE_FORMATADA =
      "4126 2612 3456 7800 0195 5500 1000 0000 0110 0000 0012";

  private final DanfePdfService service = new DanfePdfService();

  @Test
  @DisplayName("gerarPdf deve produzir um PDF nao vazio com assinatura %PDF")
  void gerarPdfShouldProduceNonEmptyPdf() {
    byte[] pdf = service.gerarPdf(buildNota());

    assertThat(pdf).isNotEmpty();
    // Assinatura de arquivo PDF: bytes "%PDF"
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  @DisplayName("gerarPdf deve funcionar mesmo com totais e tributos nulos")
  void gerarPdfShouldHandleNullTotals() {
    NotaFiscal nota = buildNota();
    nota.setValorTotalIbs(null);
    nota.setValorTotalCbs(null);

    byte[] pdf = service.gerarPdf(nota);

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  // -------------------------------------------------------------------------
  // Conteúdo e paginação (revisão do layout, issue #89)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Rodapé conta páginas, não itens: 3 itens numa página = Página 1 de 1")
  void rodapeContaPaginasNaoItens() throws IOException {
    NotaFiscal nota = buildNota();
    nota.addItem(item(2));
    nota.addItem(item(3));

    byte[] pdf = service.gerarPdf(nota);

    assertThat(paginas(pdf)).isEqualTo(1);
    assertThat(texto(pdf)).contains("Página 1 de 1").doesNotContain("de 3");
  }

  @Test
  @DisplayName("Nota com muitos itens: cada página diz Página N de total")
  void variasPaginasNumeradasPeloTotal() throws IOException {
    NotaFiscal nota = buildNota();
    for (int i = 2; i <= 60; i++) {
      nota.addItem(item(i));
    }

    byte[] pdf = service.gerarPdf(nota);
    int total = paginas(pdf);

    assertThat(total).isGreaterThan(1);
    String texto = texto(pdf);
    for (int pagina = 1; pagina <= total; pagina++) {
      assertThat(texto).contains("Página " + pagina + " de " + total);
    }
    assertThat(texto).doesNotContain("de 60");
  }

  @Test
  @DisplayName("DANFE traz emitente, destinatário e itens completos, com documentos formatados")
  void danfeTrazDadosCompletos() throws IOException {
    String texto = texto(service.gerarPdf(buildNota()));

    assertThat(texto)
        // Emitente: nome, CPF formatado, IE e endereço completo.
        .contains("Joao da Silva Produtor").contains("529.982.247-25").contains("1234567890")
        .contains("Avenida Historiador Rubens de Mendonca, 1894").contains("Bosque da Saude")
        .contains("Cuiaba").contains("78050-000")
        // Destinatário: documento formatado, endereço, CEP e IE.
        .contains("Jose Comprador").contains("987.654.321-00").contains("Rua das Flores, 100")
        .contains("78000-000").contains("9876543210")
        // Itens: código, NCM, CFOP.
        .contains("SOJA-01").contains("1201.90.00").contains("6101")
        // Operação, chave em blocos e protocolo.
        .contains("Venda de producao rural").contains("SAÍDA").contains(CHAVE_FORMATADA)
        .contains("141260000012345");
  }

  @Test
  @DisplayName("A logo do AgroContábil vai embutida no PDF")
  void logoEmbutida() {
    String bruto = new String(service.gerarPdf(buildNota()), StandardCharsets.ISO_8859_1);

    assertThat(bruto).contains("/Subtype/Image");
  }

  // -------------------------------------------------------------------------
  // Preview de rascunho (issue #89)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("Preview leva a marca d'agua de rascunho e deixa a chave em branco")
  void previewTemMarcaDaguaESemChave() throws IOException {
    NotaFiscal nota = buildNota();
    nota.setStatus(StatusNotaFiscal.RASCUNHO);
    nota.setXmlGerado(null);

    String texto = texto(service.gerarPreview(nota));

    assertThat(texto).contains("RASCUNHO").contains("SEM VALOR FISCAL")
        .contains("Jose Comprador")
        .doesNotContain(CHAVE).doesNotContain(CHAVE_FORMATADA)
        .doesNotContain("(nao disponivel)");
  }

  @Test
  @DisplayName("Preview nunca mostra chave, mesmo que a nota carregue uma")
  void previewIgnoraChaveDaNota() throws IOException {
    String texto = texto(service.gerarPreview(buildNota()));

    assertThat(texto).doesNotContain(CHAVE).doesNotContain(CHAVE_FORMATADA);
  }

  @Test
  @DisplayName("DANFE de verdade nao leva a marca d'agua")
  void danfeSemMarcaDagua() throws IOException {
    String texto = texto(service.gerarPdf(buildNota()));

    assertThat(texto).doesNotContain("SEM VALOR FISCAL").contains(CHAVE_FORMATADA);
  }

  // -------------------------------------------------------------------------
  // NT 2026.010 — DANFE da reforma tributária (issue #279)
  // -------------------------------------------------------------------------

  /** Tratamentos do item: sem redução, in natura (60%) e alíquota zero (Anexo I, 100%). */
  enum Tratamento {
    SEM_REDUCAO("000001", "0", "1.00", "9.00"),
    REDUCAO_60("200036", "60", "0.40", "3.60"),
    ALIQUOTA_ZERO("200003", "100", "0.00", "0.00");

    final String cClassTrib;
    final BigDecimal reducao;
    final BigDecimal valorIbs;
    final BigDecimal valorCbs;

    Tratamento(String cClassTrib, String reducao, String valorIbs, String valorCbs) {
      this.cClassTrib = cClassTrib;
      this.reducao = new BigDecimal(reducao);
      this.valorIbs = new BigDecimal(valorIbs);
      this.valorCbs = new BigDecimal(valorCbs);
    }
  }

  @ParameterizedTest
  @EnumSource(Tratamento.class)
  @DisplayName("Valores impressos são os do XML: CST/cClassTrib, base, alíquotas (efetiva com"
      + " gRed), parcelas do IBS, CBS e o total do IBS/CBS/IS")
  void valoresImpressosSaoOsDoXml(Tratamento tratamento) throws IOException {
    NotaFiscal nota = notaComTratamento(tratamento);
    String xml = xml(nota);
    nota.setXmlGerado(xml);
    String det = trecho(xml, "<det ", "</det>");
    String total = trecho(xml, "<IBSCBSTot>", "</IBSCBSTot>");

    String texto = texto(service.gerarPdf(nota));

    String cst = tag(det, "CST", 0);
    assertThat(cst).isEqualTo(tratamento.cClassTrib.substring(0, 3));
    assertThat(texto)
        .contains("CST " + cst)
        .contains("[cClassTrib " + tag(det, "cClassTrib", 0) + "]")
        .contains("CFOP 6101")
        .contains("1.000,00");
    // Base cheia (LC 214: a redução é de alíquota): vBC igual ao valor do item.
    assertThat(tag(det, "vBC", 0)).isEqualTo("1000.00");

    boolean comGRed = det.contains("<gRed>");
    assertThat(comGRed).isEqualTo(tratamento != Tratamento.SEM_REDUCAO);
    // Alíquota impressa: pAliqEfet com gRed; a vigente (pIBSUF/pIBSMun/pCBS) sem.
    String ibsUf = trecho(det, "<gIBSUF>", "</gIBSUF>");
    String ibsMun = trecho(det, "<gIBSMun>", "</gIBSMun>");
    String cbs = trecho(det, "<gCBS>", "</gCBS>");
    assertThat(texto)
        .contains("CBS " + percentual(comGRed ? tag(cbs, "pAliqEfet", 0) : tag(cbs, "pCBS", 0)))
        .contains("IBS UF "
            + percentual(comGRed ? tag(ibsUf, "pAliqEfet", 0) : tag(ibsUf, "pIBSUF", 0)))
        .contains("IBS MUN "
            + percentual(comGRed ? tag(ibsMun, "pAliqEfet", 0) : tag(ibsMun, "pIBSMun", 0)))
        .contains("CBS " + moeda(tag(cbs, "vCBS", 0)))
        .contains("IBS UF " + moeda(tag(ibsUf, "vIBSUF", 0)))
        .contains("IBS MUN " + moeda(tag(ibsMun, "vIBSMun", 0)));
    if (comGRed) {
      // A alíquota cheia (0,90% da CBS) não aparece: só a efetiva.
      assertThat(texto).doesNotContain("CBS 0,90%");
    }

    // Bloco TOTAL DO IBS / CBS / IS (item 4.1) com os totais do IBSCBSTot.
    assertThat(texto).contains("TOTAL DO IBS / CBS / IS")
        .contains("VALOR DA CBS").contains("VALOR DO IBS UF")
        .contains("VALOR DO IBS MUNICÍPIO").contains("VALOR DO IMPOSTO SELETIVO");
    String totalUf = trecho(total, "<gIBSUF>", "</gIBSUF>");
    String totalMun = trecho(total, "<gIBSMun>", "</gIBSMun>");
    String totalCbs = trecho(total, "<gCBS>", "</gCBS>");
    assertThat(valorDoRotulo(texto, "VALOR DA CBS")).isEqualTo(moeda(tag(totalCbs, "vCBS", 0)));
    assertThat(valorDoRotulo(texto, "VALOR DO IBS UF"))
        .isEqualTo(moeda(tag(totalUf, "vIBSUF", 0)));
    assertThat(valorDoRotulo(texto, "VALOR DO IBS MUNICÍPIO"))
        .isEqualTo(moeda(tag(totalMun, "vIBSMun", 0)));
    // O XML não tem o grupo do IS: o campo sai vazio (nada de informação inexistente).
    assertThat(valorDoRotulo(texto, "VALOR DO IMPOSTO SELETIVO")).doesNotMatch("[0-9,.]+");
    assertThat(tag(totalCbs, "vCBS", 0)).isEqualTo(tratamento.valorCbs.toPlainString());

    // O preview (sem XML, pela nota) imprime os mesmos valores fiscais que a DANFE do XML.
    nota.setXmlGerado(null);
    assertThat(quadrosFiscais(texto(service.gerarPreview(nota))))
        .isEqualTo(quadrosFiscais(texto));
  }

  @Test
  @DisplayName("Com XML, os valores fiscais saem do XML, não da nota (nota importada de terceiro)")
  void notaImportadaUsaOXml() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.SEM_REDUCAO);
    // XML do fornecedor: ICMS destacado, CRT 1, bairro próprio e o CST/cClassTrib dele; a nota
    // guarda o cClassTrib do crédito (outro), sem alíquotas nem totais de IBS/CBS.
    String xml = xml(nota)
        .replace("<vBC>0.00</vBC><vICMS>0.00</vICMS>", "<vBC>1000.00</vBC><vICMS>120.00</vICMS>")
        .replace("<vFrete>0.00</vFrete>", "<vFrete>50.00</vFrete>")
        .replace("<CRT>3</CRT>", "<CRT>1</CRT>")
        .replace("<xBairro>CENTRO</xBairro>", "<xBairro>Distrito Industrial</xBairro>");
    assertThat(xml).contains("<vICMS>120.00</vICMS>").contains("<CRT>1</CRT>")
        .contains("Distrito Industrial");
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setEmitenteNome("Fornecedor de Insumos Ltda");
    nota.setValorTotalIbs(null);
    nota.setValorTotalCbs(null);
    NotaFiscalItem item = nota.getItens().get(0);
    item.setCClassTribAplicado("200036");
    item.setAliquotaIbs(null);
    item.setAliquotaCbs(null);
    nota.setXmlGerado(xml);

    String texto = texto(service.gerarPdf(nota));

    assertThat(valorDoRotulo(texto, "VALOR DO ICMS")).isEqualTo("120,00");
    assertThat(valorDoRotulo(texto, "BASE DE CÁLC. DO ICMS")).isEqualTo("1.000,00");
    assertThat(valorDoRotulo(texto, "VALOR DO FRETE")).isEqualTo("50,00");
    assertThat(valorDoRotulo(texto, "CÓDIGO DE REGIME TRIBUTÁRIO (CRT)"))
        .isEqualTo("1 - SIMPLES NACIONAL");
    assertThat(texto).contains("Distrito Industrial").contains("CST 000")
        .contains("[cClassTrib 000001]").doesNotContain("200036")
        .contains("CBS 0,90%").contains("CBS 9,00")
        .contains("Origem: importada (nota de terceiro)")
        // A nota de terceiro não leva a assinatura do AgroContábil.
        .doesNotContain("Emitido com o AgroContábil");
    assertThat(valorDoRotulo(texto, "VALOR DA CBS")).isEqualTo("9,00");
  }

  @Test
  @DisplayName("XML ilegível falha a geração (nunca cai em silêncio para os valores da nota)")
  void xmlIlegivelFalha() {
    NotaFiscal nota = buildNota();
    nota.setXmlGerado("<NFe/>");

    assertThatThrownBy(() -> service.gerarPdf(nota))
        .isInstanceOf(DanfeGenerationException.class);
  }

  @Test
  @DisplayName("DANFE de NF-e cancelada leva a marca d'água e a situação")
  void notaCanceladaComMarcaDagua() throws IOException {
    NotaFiscal nota = buildNota();
    nota.setStatus(StatusNotaFiscal.CANCELADA);

    String texto = texto(service.gerarPdf(nota));

    assertThat(texto).contains("NF-e CANCELADA").contains("Situação: CANCELADA");
  }

  @Test
  @DisplayName("Informações complementares trazem documento, situação e origem")
  void situacaoNasInformacoesComplementares() throws IOException {
    String texto = texto(service.gerarPdf(buildNota()));

    assertThat(texto).contains("Documento: NF-e").contains("Situação: XML GERADO")
        .contains("Origem: emitida pelo produtor").contains("Emitido com o AgroContábil")
        .doesNotContain("NF-e CANCELADA");
  }

  @Test
  @DisplayName("Emitente traz o CRT do XML; o tipo de regime de apuração fica reservado e vazio")
  void crtERegimeDeApuracaoReservado() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.REDUCAO_60);
    String xml = xml(nota);
    nota.setXmlGerado(xml);

    String texto = texto(service.gerarPdf(nota));

    assertThat(tag(xml, "CRT", 0)).isEqualTo("3");
    assertThat(texto).contains("CÓDIGO DE REGIME TRIBUTÁRIO (CRT)").contains("3 - REGIME NORMAL")
        .contains("TIPO DE REGIME DE APURAÇÃO DO IBS/CBS");
    assertThat(valorDoRotulo(texto, "CÓDIGO DE REGIME TRIBUTÁRIO (CRT)"))
        .isEqualTo("3 - REGIME NORMAL");
    // O campo reservado não tem valor: a linha seguinte ao rótulo já é o quadro do destinatário.
    assertThat(valorDoRotulo(texto, "TIPO DE REGIME DE APURAÇÃO DO IBS/CBS"))
        .isEqualTo("DESTINATÁRIO / REMETENTE");
  }

  @Test
  @DisplayName("Quadros facultativos sem dado no XML não aparecem (NT 2026.010, item 4.4)")
  void quadrosFacultativosAusentes() throws IOException {
    String texto = texto(service.gerarPdf(notaComTratamento(Tratamento.SEM_REDUCAO)));

    assertThat(texto.toUpperCase(Locale.ROOT))
        .doesNotContain("FATURA").doesNotContain("DUPLICATA")
        .doesNotContain("TRANSPORTADOR").doesNotContain("ISSQN")
        .doesNotContain("MONOF").doesNotContain("FCP").doesNotContain("DIFAL");
  }

  @Test
  @DisplayName("Quadro do ICMS/IPI preservado, com os zeros do XML")
  void quadroIcmsIpiComZeros() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.SEM_REDUCAO);
    String xml = xml(nota);
    nota.setXmlGerado(xml);

    String texto = texto(service.gerarPdf(nota));

    assertThat(tag(xml, "vICMS", 0)).isEqualTo("0.00");
    assertThat(valorDoRotulo(texto, "VALOR DO ICMS")).isEqualTo("0,00");
    assertThat(valorDoRotulo(texto, "VALOR TOTAL DO IPI")).isEqualTo("0,00");
    assertThat(valorDoRotulo(texto, "VALOR TOTAL DA NOTA")).isEqualTo("1.000,00");
  }

  @Test
  @DisplayName("Preview da DANFE da reforma mantém a marca d'água e os tributos do item")
  void previewDaReformaComMarcaDagua() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.REDUCAO_60);

    String texto = texto(service.gerarPreview(nota));

    assertThat(texto).contains("RASCUNHO").contains("SEM VALOR FISCAL")
        .contains("CST 200").contains("[cClassTrib 200036]").contains("CBS 0,36%")
        .contains("TOTAL DO IBS / CBS / IS");
  }

  @Test
  @DisplayName("Produtor pessoa física em SIMPLES (cadastro antigo) sai como CRT 3 no XML e no preview (#298)")
  void pessoaFisicaComRegimeSimplesSaiRegimeNormal() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.REDUCAO_60);
    nota.getProdutor().setRegimeTributario(RegimeTributario.SIMPLES);

    String preview = texto(service.gerarPreview(nota));
    assertThat(valorDoRotulo(preview, "CÓDIGO DE REGIME TRIBUTÁRIO (CRT)"))
        .isEqualTo("3 - REGIME NORMAL");

    String xml = xml(nota);
    nota.setXmlGerado(xml);
    assertThat(tag(xml, "CRT", 0)).isEqualTo("3");
    assertThat(valorDoRotulo(texto(service.gerarPdf(nota)), "CÓDIGO DE REGIME TRIBUTÁRIO (CRT)"))
        .isEqualTo("3 - REGIME NORMAL");
  }

  @Test
  @DisplayName("Venda de PF a empresa: Dados Adicionais com o Funrural/Senar no preview e do XML (#332)")
  void funruralNosDadosAdicionais() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.REDUCAO_60);
    nota.setDestinatarioCpfCnpj("11444777000161");
    String esperado = NotaFiscalNfeMapper.textoFunrural(nota).orElseThrow();

    assertThat(semQuebra(texto(service.gerarPreview(nota))))
        .contains("FUNRURAL/SENAR RETIDO PELO ADQUIRENTE: BASE R$ 1.000,00")
        .contains("TOTAL 1,5% R$ 15,00");

    String xml = xml(nota);
    nota.setXmlGerado(xml);
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    assertThat(xml).contains(esperado);
    assertThat(semQuebra(texto(service.gerarPdf(nota))))
        .contains("FUNRURAL/SENAR RETIDO PELO ADQUIRENTE: BASE R$ 1.000,00")
        .contains("ART. 30, III E IV");
  }

  @Test
  @DisplayName("Venda de PF a pessoa física: Dados Adicionais sem o Funrural (#332)")
  void semFunruralVendaAPessoaFisica() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.REDUCAO_60); // destinatário com CPF

    assertThat(texto(service.gerarPreview(nota))).doesNotContain("FUNRURAL");
  }

  @Test
  @DisplayName("Nota importada: o Funrural do XML do terceiro não entra nos Dados Adicionais (#332)")
  void importadaNaoImprimeFunrural() throws IOException {
    NotaFiscal nota = notaComTratamento(Tratamento.REDUCAO_60);
    nota.setDestinatarioCpfCnpj("11444777000161");
    nota.setXmlGerado(xml(nota));
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);
    nota.setEmitenteNome("Fornecedor Ltda");
    nota.setEmitenteCpfCnpj("11222333000181");

    assertThat(texto(service.gerarPdf(nota))).doesNotContain("FUNRURAL");
  }

  private static String semQuebra(String texto) {
    return texto.replaceAll("\\s+", " ");
  }

  /** Nota de soja de 1.000,00 em 2026, com o tratamento já aplicado ao item (como o cálculo grava). */
  private NotaFiscal notaComTratamento(Tratamento tratamento) {
    NotaFiscal nota = buildNota();
    nota.setDataEmissao(OffsetDateTime.parse("2026-11-10T10:00:00-03:00"));
    nota.setValorTotalIbs(tratamento.valorIbs);
    nota.setValorTotalCbs(tratamento.valorCbs);
    NotaFiscalItem item = nota.getItens().get(0);
    item.setCClassTribAplicado(tratamento.cClassTrib);
    item.setPercentualReducaoAplicado(tratamento.reducao);
    item.setAliquotaIbsUf(new BigDecimal("0.0010"));
    item.setAliquotaIbsMun(BigDecimal.ZERO);
    item.setValorIbs(tratamento.valorIbs);
    item.setValorIbsUf(tratamento.valorIbs);
    item.setValorIbsMun(new BigDecimal("0.00"));
    item.setValorCbs(tratamento.valorCbs);
    return nota;
  }

  private static String xml(NotaFiscal nota) {
    return new XmlGenerator().marshal(
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()).toNfe(nota));
  }

  /**
   * Do quadro do emitente aos dados adicionais (CRT, cálculo do imposto, IBS/CBS/IS e itens),
   * sem o aviso nem a marca d'água do preview.
   */
  private static String quadrosFiscais(String texto) {
    int de = texto.indexOf("CÓDIGO DE REGIME TRIBUTÁRIO (CRT)");
    int ate = texto.indexOf("DADOS ADICIONAIS");
    assertThat(de).isGreaterThanOrEqualTo(0);
    return texto.substring(de, ate).lines().map(String::strip)
        .filter(l -> !l.isEmpty() && !l.contains("RASCUNHO") && !l.contains("SEM VALOR"))
        .toList().toString();
  }

  /** Conteúdo da {@code ocorrencia}-ésima tag (0 = a primeira). */
  private static String tag(String xml, String nome, int ocorrencia) {
    Matcher m = Pattern.compile("<" + nome + ">([^<]*)</" + nome + ">").matcher(xml);
    for (int i = 0; i <= ocorrencia; i++) {
      assertThat(m.find()).as("tag %s no XML", nome).isTrue();
    }
    return m.group(1);
  }

  private static String trecho(String xml, String inicio, String fim) {
    int de = xml.indexOf(inicio);
    assertThat(de).as("%s no XML", inicio).isGreaterThanOrEqualTo(0);
    return xml.substring(de, xml.indexOf(fim, de) + fim.length());
  }

  /** A linha de texto (sem espaços nas pontas) logo depois da linha do rótulo. */
  private static String valorDoRotulo(String texto, String rotulo) {
    List<String> linhas = texto.lines().map(String::strip).toList();
    int i = linhas.indexOf(rotulo);
    assertThat(i).as("rótulo %s no PDF", rotulo).isGreaterThanOrEqualTo(0);
    return i + 1 < linhas.size() ? linhas.get(i + 1) : "";
  }

  /** Percentual do XML ({@code 0.3600}) como a DANFE imprime ({@code 0,36%}). */
  private static String percentual(String xml) {
    NumberFormat nf = NumberFormat.getNumberInstance(Locale.of("pt", "BR"));
    nf.setMinimumFractionDigits(2);
    nf.setMaximumFractionDigits(4);
    return nf.format(new BigDecimal(xml)) + "%";
  }

  /** Valor do XML ({@code 3.60}) como a DANFE imprime ({@code 3,60}). */
  private static String moeda(String xml) {
    NumberFormat nf = NumberFormat.getNumberInstance(Locale.of("pt", "BR"));
    nf.setMinimumFractionDigits(2);
    nf.setMaximumFractionDigits(2);
    return nf.format(new BigDecimal(xml));
  }

  private static int paginas(byte[] pdf) throws IOException {
    PdfReader leitor = new PdfReader(pdf);
    try {
      return leitor.getNumberOfPages();
    } finally {
      leitor.close();
    }
  }

  private static String texto(byte[] pdf) throws IOException {
    PdfReader leitor = new PdfReader(pdf);
    try {
      StringBuilder texto = new StringBuilder();
      PdfTextExtractor extrator = new PdfTextExtractor(leitor);
      for (int pagina = 1; pagina <= leitor.getNumberOfPages(); pagina++) {
        texto.append(extrator.getTextFromPage(pagina)).append('\n');
      }
      return texto.toString();
    } finally {
      leitor.close();
    }
  }

  private NotaFiscal buildNota() {
    Produtor produtor = ProdutorTestFixtures.produtorCompleto();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("98765432100")
        .destinatarioIe("9876543210")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua das Flores")
            .numero("100")
            .municipio("Cuiaba")
            .uf("MT")
            .cep("78000000")
            .build())
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .valorTotalIbs(new BigDecimal("1.00"))
        .valorTotalCbs(new BigDecimal("9.00"))
        .status(StatusNotaFiscal.XML_GERADO)
        .chaveAcesso(CHAVE)
        .protocoloAutorizacao("141260000012345")
        .build();

    nota.addItem(item(1));
    return nota;
  }

  private static NotaFiscalItem item(int numero) {
    return NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(numero)
        .codigoProduto(numero == 1 ? "SOJA-01" : "ITEM-" + numero)
        .descricao("Soja em graos")
        .ncm("12019000")
        .cfop("6101")
        .unidade("KG")
        .quantidade(new BigDecimal("500.0000"))
        .valorUnitario(new BigDecimal("2.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .aliquotaIbs(new BigDecimal("0.0010"))
        .valorIbs(new BigDecimal("1.00"))
        .aliquotaCbs(new BigDecimal("0.0090"))
        .valorCbs(new BigDecimal("9.00"))
        .build();
  }
}
