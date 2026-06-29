package br.com.agrocontabil.danfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
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
