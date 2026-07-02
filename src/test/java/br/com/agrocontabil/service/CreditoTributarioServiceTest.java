package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link CreditoTributarioService} (issue Agrocontabil#30 — RN003).
 *
 * <p>Serviço puro (sem banco): usa {@link TributosProperties} reais com as alíquotas de
 * referência do ano-teste 2026 (IBS 0,10 %, CBS 0,90 %) e aproveitamento integral (100 %),
 * sobrescritos por teste quando o cenário exige.
 */
class CreditoTributarioServiceTest {

  private TributosProperties tributosProperties;
  private CreditoTributarioService service;

  @BeforeEach
  void setUp() {
    tributosProperties = new TributosProperties();
    service = new CreditoTributarioService(tributosProperties);
  }

  @Test
  @DisplayName("Nota IMPORTADA com item CFOP 6101 (venda do fornecedor) gera credito IBS/CBS")
  void notaImportadaComCfopSaidaDoEmitenteGeraCredito() {
    NotaFiscal nota = notaImportada(item("6101", "1000.00"));

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    // base 1000.00 → IBS = 1000 × 0,0010 = 1.00; CBS = 1000 × 0,0090 = 9.00 (aproveitamento 100%)
    assertThat(item.getValorCreditoIbs()).isEqualByComparingTo("1.00");
    assertThat(item.getValorCreditoCbs()).isEqualByComparingTo("9.00");
    assertThat(nota.getValorTotalCreditoIbs()).isEqualByComparingTo("1.00");
    assertThat(nota.getValorTotalCreditoCbs()).isEqualByComparingTo("9.00");
  }

  @Test
  @DisplayName("Item com CFOP 1xxx (entrada na perspectiva do destinatario) tambem e elegivel")
  void cfopEntradaElegivel() {
    NotaFiscal nota = notaImportada(item("1556", "500.00"));

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getValorCreditoIbs()).isEqualByComparingTo("0.50");
    assertThat(item.getValorCreditoCbs()).isEqualByComparingTo("4.50");
  }

  @Test
  @DisplayName("Item com CFOP 7xxx (exportacao) nao gera credito")
  void cfopExportacaoNaoGeraCredito() {
    NotaFiscal nota = notaImportada(item("7101", "1000.00"));

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getValorCreditoIbs()).isNull();
    assertThat(item.getValorCreditoCbs()).isNull();
    assertThat(nota.getValorTotalCreditoIbs()).isEqualByComparingTo("0.00");
    assertThat(nota.getValorTotalCreditoCbs()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("Item com CFOP nulo ou invalido nao gera credito (conservador)")
  void cfopInvalidoNaoGeraCredito() {
    NotaFiscal nota = notaImportada(item(null, "1000.00"), item("9999", "1000.00"));

    service.calcular(nota);

    assertThat(nota.getItens().get(0).getValorCreditoIbs()).isNull();
    assertThat(nota.getItens().get(1).getValorCreditoIbs()).isNull();
    assertThat(nota.getValorTotalCreditoIbs()).isEqualByComparingTo("0.00");
    assertThat(nota.getValorTotalCreditoCbs()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("Nota PROPRIA (saida) nao recebe credito nem totais")
  void notaPropriaNaoRecebeCredito() {
    NotaFiscal nota = notaImportada(item("6101", "1000.00"));
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);

    service.calcular(nota);

    assertThat(nota.getItens().get(0).getValorCreditoIbs()).isNull();
    assertThat(nota.getItens().get(0).getValorCreditoCbs()).isNull();
    assertThat(nota.getValorTotalCreditoIbs()).isNull();
    assertThat(nota.getValorTotalCreditoCbs()).isNull();
  }

  @Test
  @DisplayName("Percentual de aproveitamento parametrizado (50%) reduz o credito pela metade")
  void percentualAproveitamentoParametrizado() {
    tributosProperties.getCredito().setPercentualAproveitamento(new BigDecimal("50"));
    NotaFiscal nota = notaImportada(item("6101", "1000.00"));

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getValorCreditoIbs()).isEqualByComparingTo("0.50");
    assertThat(item.getValorCreditoCbs()).isEqualByComparingTo("4.50");
  }

  @Test
  @DisplayName("Percentual de reducao da base do item e respeitado no credito")
  void percentualReducaoDaBaseRespeitado() {
    NotaFiscalItem item = item("6101", "1000.00");
    item.setPercentualReducao(new BigDecimal("60"));
    NotaFiscal nota = notaImportada(item);

    service.calcular(nota);

    // base = 1000 × (1 − 0,60) = 400.00 → IBS 0.40; CBS 3.60
    assertThat(nota.getItens().get(0).getValorCreditoIbs()).isEqualByComparingTo("0.40");
    assertThat(nota.getItens().get(0).getValorCreditoCbs()).isEqualByComparingTo("3.60");
  }

  @Test
  @DisplayName("Totais da nota somam apenas os itens elegiveis")
  void totaisSomamApenasElegiveis() {
    NotaFiscal nota = notaImportada(
        item("6101", "1000.00"),   // elegivel: IBS 1.00 / CBS 9.00
        item("7101", "9999.00"),   // exportacao: fora
        item("5102", "2000.00"));  // elegivel: IBS 2.00 / CBS 18.00

    service.calcular(nota);

    assertThat(nota.getValorTotalCreditoIbs()).isEqualByComparingTo("3.00");
    assertThat(nota.getValorTotalCreditoCbs()).isEqualByComparingTo("27.00");
  }

  // ---------------------------------------------------------------------------------------

  private NotaFiscal notaImportada(NotaFiscalItem... itens) {
    NotaFiscal nota = NotaFiscal.builder()
        .origem(OrigemNotaFiscal.IMPORTADA)
        .build();
    for (NotaFiscalItem item : itens) {
      nota.addItem(item);
    }
    return nota;
  }

  private NotaFiscalItem item(String cfop, String valorTotal) {
    return NotaFiscalItem.builder()
        .cfop(cfop)
        .valorTotal(new BigDecimal(valorTotal))
        .build();
  }
}
