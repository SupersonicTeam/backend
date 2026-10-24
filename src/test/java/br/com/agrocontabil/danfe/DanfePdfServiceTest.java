package br.com.agrocontabil.danfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        .contains("SOJA-01").contains("1201.00.90").contains("6101")
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
        .xmlGerado("<NFe/>")
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
        .ncm("12010090")
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
