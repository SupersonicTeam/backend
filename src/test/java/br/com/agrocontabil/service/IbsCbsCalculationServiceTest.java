package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.TaxProperties;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import br.com.agrocontabil.model.Producer;
import br.com.agrocontabil.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link IbsCbsCalculationService}.
 *
 * <p>Referência legal para todos os casos:
 * <ul>
 *   <li><strong>EC 132/2023</strong> — institui IBS e CBS.</li>
 *   <li><strong>LC 214/2025</strong> — regulamenta o cálculo (arts. 12–18 — base; 87–92 —
 *       alíquotas). Para 2026 (ano-teste): CBS 0,90 % e IBS 0,10 % (referência técnica).</li>
 * </ul>
 *
 * <p>Fórmulas testadas:
 * <pre>
 *   calculationBase = totalValue * (1 - reductionPercentage / 100)
 *   ibsValue    = calculationBase * ibsRate    — HALF_UP 2 casas
 *   cbsValue    = calculationBase * cbsRate    — HALF_UP 2 casas
 * </pre>
 *
 * <p>Casos cobertos:
 * <ol>
 *   <li>Alíquota cheia, sem redução (reductionPercentage = 0)</li>
 *   <li>Com redução de 60 % na base (agropecuária, LC 214/2025 — valor hipotético para teste;
 *       percentual real será regulamentado)</li>
 *   <li>Arredondamento HALF_UP: totalValue que gera fração na 3.ª casa decimal</li>
 *   <li>Item de valor zero (base zero, IBS/CBS zero)</li>
 *   <li>Múltiplos items — agregação correta nos totais da invoice</li>
 * </ol>
 *
 * <p>Este serviço não acessa banco; usa Mockito zero (teste PURO sem contexto Spring).
 */
class IbsCbsCalculationServiceTest {

  /**
   * Alíquotas de teste 2026 (EC 132/2023 + LC 214/2025):
   * IBS  = 0,10 % (estadual/municipal)
   * CBS  = 0,90 % (federal)
   */
  private static final BigDecimal ALIQUOTA_IBS = new BigDecimal("0.0010");
  private static final BigDecimal ALIQUOTA_CBS = new BigDecimal("0.0090");

  private IbsCbsCalculationService service;

  @BeforeEach
  void setUp() {
    TaxProperties props = new TaxProperties();
    props.getIbs().setAliquota(ALIQUOTA_IBS);
    props.getCbs().setAliquota(ALIQUOTA_CBS);
    service = new IbsCbsCalculationService(props);
  }

  // -------------------------------------------------------------------------
  // Caso 1: alíquota cheia, sem redução
  // -------------------------------------------------------------------------

  /**
   * Item: totalValue = R$ 1.000,00, reductionPercentage = 0.
   * EC 132/2023 + LC 214/2025 (ano-teste 2026):
   *   calculationBase = 1000.00 * 1.0000 = 1000.00
   *   ibsValue    = 1000.00 * 0.0010 = 1.00
   *   cbsValue    = 1000.00 * 0.0090 = 9.00
   */
  @Test
  @DisplayName("deve calcular IBS e CBS sem reducao (aliquota cheia) — LC 214/2025 ano-teste 2026")
  void shouldCalculateFullRateWithNoReduction() {
    Invoice invoice = buildNotaComUmItem(new BigDecimal("1000.00"), BigDecimal.ZERO);

    service.calculate(invoice);

    InvoiceItem item = invoice.getItems().get(0);
    assertThat(item.getIbsCbsCalculationBase()).isEqualByComparingTo("1000.00");
    assertThat(item.getIbsRate()).isEqualByComparingTo(ALIQUOTA_IBS);
    assertThat(item.getIbsValue()).isEqualByComparingTo("1.00");
    assertThat(item.getCbsRate()).isEqualByComparingTo(ALIQUOTA_CBS);
    assertThat(item.getCbsValue()).isEqualByComparingTo("9.00");

    assertThat(invoice.getTotalIbsValue()).isEqualByComparingTo("1.00");
    assertThat(invoice.getTotalCbsValue()).isEqualByComparingTo("9.00");
  }

  // -------------------------------------------------------------------------
  // Caso 2: com redução de 60 % na base
  // -------------------------------------------------------------------------

  /**
   * Redução hipotética de 60 % para produtos agropecuários (LC 214/2025 prevê
   * regimes diferenciados; percentual exato por produto será regulamentado).
   * Item: totalValue = R$ 1.000,00, reductionPercentage = 60.
   *   calculationBase = 1000.00 * (1 - 0.60) = 1000.00 * 0.40 = 400.00
   *   ibsValue    = 400.00 * 0.0010 = 0.40
   *   cbsValue    = 400.00 * 0.0090 = 3.60
   */
  @Test
  @DisplayName("deve reduzir base de calculo em 60% (regime diferenciado agropecuario) — LC 214/2025")
  void shouldApply60PercentReductionToBase() {
    Invoice invoice = buildNotaComUmItem(new BigDecimal("1000.00"), new BigDecimal("60"));

    service.calculate(invoice);

    InvoiceItem item = invoice.getItems().get(0);
    assertThat(item.getIbsCbsCalculationBase()).isEqualByComparingTo("400.00");
    assertThat(item.getIbsValue()).isEqualByComparingTo("0.40");
    assertThat(item.getCbsValue()).isEqualByComparingTo("3.60");

    assertThat(invoice.getTotalIbsValue()).isEqualByComparingTo("0.40");
    assertThat(invoice.getTotalCbsValue()).isEqualByComparingTo("3.60");
  }

  // -------------------------------------------------------------------------
  // Caso 3: arredondamento HALF_UP a 2 casas decimais
  // -------------------------------------------------------------------------

  /**
   * Escolhido um totalValue que gere fração na 3.ª casa decimal para cada tributo.
   * Item: totalValue = R$ 333,33, reductionPercentage = 0.
   *   calculationBase = 333.33
   *   ibsValue    = 333.33 * 0.0010 = 0.33333 → arredonda HALF_UP → 0.33
   *   cbsValue    = 333.33 * 0.0090 = 2.99997 → arredonda HALF_UP → 3.00
   */
  @Test
  @DisplayName("deve aplicar arredondamento HALF_UP com 2 casas decimais — LC 214/2025, art. 12")
  void shouldApplyHalfUpRoundingToTwoDecimalPlaces() {
    Invoice invoice = buildNotaComUmItem(new BigDecimal("333.33"), BigDecimal.ZERO);

    service.calculate(invoice);

    InvoiceItem item = invoice.getItems().get(0);
    assertThat(item.getIbsValue()).isEqualByComparingTo("0.33");
    assertThat(item.getCbsValue()).isEqualByComparingTo("3.00");
  }

  // -------------------------------------------------------------------------
  // Caso 4: item de valor zero
  // -------------------------------------------------------------------------

  /**
   * Item com totalValue = R$ 0,00 (ex.: bonificação sem valor fiscal).
   * Base e tributos devem ser zero.
   */
  @Test
  @DisplayName("deve retornar IBS e CBS zero para item de valor zero")
  void shouldReturnZeroForZeroValueItem() {
    Invoice invoice = buildNotaComUmItem(BigDecimal.ZERO, BigDecimal.ZERO);

    service.calculate(invoice);

    InvoiceItem item = invoice.getItems().get(0);
    assertThat(item.getIbsCbsCalculationBase()).isEqualByComparingTo("0.00");
    assertThat(item.getIbsValue()).isEqualByComparingTo("0.00");
    assertThat(item.getCbsValue()).isEqualByComparingTo("0.00");

    assertThat(invoice.getTotalIbsValue()).isEqualByComparingTo("0.00");
    assertThat(invoice.getTotalCbsValue()).isEqualByComparingTo("0.00");
  }

  // -------------------------------------------------------------------------
  // Caso 5: múltiplos items — agregação de totais
  // -------------------------------------------------------------------------

  /**
   * Dois items:
   *   Item 1: totalValue = R$ 500,00, sem redução → IBS = 0.50, CBS = 4.50
   *   Item 2: totalValue = R$ 200,00, sem redução → IBS = 0.20, CBS = 1.80
   *   Total IBS = 0.70, Total CBS = 6.30
   */
  @Test
  @DisplayName("deve agregar valorTotalIbs e valorTotalCbs de multiplos itens")
  void shouldAggregateTotalsForMultipleItems() {
    Invoice invoice = buildNotaVazia();

    InvoiceItem item1 = buildItem(new BigDecimal("500.00"), BigDecimal.ZERO);
    InvoiceItem item2 = buildItem(new BigDecimal("200.00"), BigDecimal.ZERO);
    invoice.addItem(item1);
    invoice.addItem(item2);

    service.calculate(invoice);

    // Item 1: IBS = 500 * 0.0010 = 0.50 ; CBS = 500 * 0.0090 = 4.50
    assertThat(item1.getIbsValue()).isEqualByComparingTo("0.50");
    assertThat(item1.getCbsValue()).isEqualByComparingTo("4.50");

    // Item 2: IBS = 200 * 0.0010 = 0.20 ; CBS = 200 * 0.0090 = 1.80
    assertThat(item2.getIbsValue()).isEqualByComparingTo("0.20");
    assertThat(item2.getCbsValue()).isEqualByComparingTo("1.80");

    // Agregação na invoice: IBS = 0.70 ; CBS = 6.30
    assertThat(invoice.getTotalIbsValue()).isEqualByComparingTo("0.70");
    assertThat(invoice.getTotalCbsValue()).isEqualByComparingTo("6.30");
  }

  // -------------------------------------------------------------------------
  // Caso extra: reductionPercentage nulo deve ser tratado como zero
  // -------------------------------------------------------------------------

  /**
   * Item sem reductionPercentage informado (null).
   * Deve ser equivalente a reductionPercentage = 0.
   * Item: totalValue = R$ 1.000,00 → IBS = 1.00, CBS = 9.00.
   */
  @Test
  @DisplayName("deve tratar percentualReducao nulo como zero (sem reducao)")
  void shouldTreatNullReductionAsZero() {
    Invoice invoice = buildNotaComUmItem(new BigDecimal("1000.00"), null);

    service.calculate(invoice);

    InvoiceItem item = invoice.getItems().get(0);
    assertThat(item.getIbsCbsCalculationBase()).isEqualByComparingTo("1000.00");
    assertThat(item.getIbsValue()).isEqualByComparingTo("1.00");
    assertThat(item.getCbsValue()).isEqualByComparingTo("9.00");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Invoice buildNotaVazia() {
    Producer producer = Producer.builder()
        .id(UUID.randomUUID())
        .fullName("Produtor Teste")
        .taxId("12345678901")
        .build();

    return Invoice.builder()
        .id(UUID.randomUUID())
        .number(1L)
        .series(1)
        .model("55")
        .operationNature("Venda de producao rural")
        .issuanceDate(OffsetDateTime.now())
        .producer(producer)
        .recipientName("Comprador Teste")
        .recipientTaxId("98765432100")
        .totalProductsValue(BigDecimal.ZERO)
        .totalInvoiceValue(BigDecimal.ZERO)
        .status(InvoiceStatus.RASCUNHO)
        .build();
  }

  private Invoice buildNotaComUmItem(BigDecimal totalValue, BigDecimal reductionPercentage) {
    Invoice invoice = buildNotaVazia();
    invoice.addItem(buildItem(totalValue, reductionPercentage));
    return invoice;
  }

  private InvoiceItem buildItem(BigDecimal totalValue, BigDecimal reductionPercentage) {
    return InvoiceItem.builder()
        .id(UUID.randomUUID())
        .itemNumber(1)
        .description("Produto Teste")
        .ncm("12010090")
        .cfop("6101")
        .unit("KG")
        .quantity(BigDecimal.ONE)
        .unitPrice(totalValue)
        .totalValue(totalValue)
        .reductionPercentage(reductionPercentage)
        .build();
  }
}
