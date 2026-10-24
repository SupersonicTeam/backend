package br.com.agrocontabil.danfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.math.BigDecimal;
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
        .doesNotContain("41262612345678000195550010000000011000000012")
        .doesNotContain("(nao disponivel)");
  }

  @Test
  @DisplayName("Preview nunca mostra chave, mesmo que a nota carregue uma")
  void previewIgnoraChaveDaNota() throws IOException {
    String texto = texto(service.gerarPreview(buildNota()));

    assertThat(texto).doesNotContain("41262612345678000195550010000000011000000012");
  }

  @Test
  @DisplayName("DANFE de verdade nao leva a marca d'agua")
  void danfeSemMarcaDagua() throws IOException {
    String texto = texto(service.gerarPdf(buildNota()));

    assertThat(texto).doesNotContain("SEM VALOR FISCAL")
        .contains("41262612345678000195550010000000011000000012");
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
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("Fazenda Boa Esperanca LTDA")
        .cpfCnpj("12345678000195")
        .estado("MT")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .descricao("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unidade("KG")
        .quantidade(new BigDecimal("500.0000"))
        .valorUnitario(new BigDecimal("2.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .valorIbs(new BigDecimal("1.00"))
        .valorCbs(new BigDecimal("9.00"))
        .build();

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
        .chaveAcesso("41262612345678000195550010000000011000000012")
        .xmlGerado("<NFe/>")
        .build();

    nota.addItem(item);
    return nota;
  }
}
