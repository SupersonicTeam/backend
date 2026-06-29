package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link CalculoIbsCbsService}.
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
 *   baseCalculo = valorTotal * (1 - percentualReducao / 100)
 *   valorIbs    = baseCalculo * aliquotaIbs    — HALF_UP 2 casas
 *   valorCbs    = baseCalculo * aliquotaCbs    — HALF_UP 2 casas
 * </pre>
 *
 * <p>Casos cobertos:
 * <ol>
 *   <li>Alíquota cheia, sem redução (percentualReducao = 0)</li>
 *   <li>Com redução de 60 % na base (agropecuária, LC 214/2025 — valor hipotético para teste;
 *       percentual real será regulamentado)</li>
 *   <li>Arredondamento HALF_UP: valorTotal que gera fração na 3.ª casa decimal</li>
 *   <li>Item de valor zero (base zero, IBS/CBS zero)</li>
 *   <li>Múltiplos itens — agregação correta nos totais da nota</li>
 * </ol>
 *
 * <p>Este serviço não acessa banco; usa Mockito zero (teste PURO sem contexto Spring).
 */
class CalculoIbsCbsServiceTest {

  /**
   * Alíquotas de teste 2026 (EC 132/2023 + LC 214/2025):
   * IBS  = 0,10 % (estadual/municipal)
   * CBS  = 0,90 % (federal)
   */
  private static final BigDecimal ALIQUOTA_IBS = new BigDecimal("0.0010");
  private static final BigDecimal ALIQUOTA_CBS = new BigDecimal("0.0090");

  private CalculoIbsCbsService service;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    props.getIbs().setAliquota(ALIQUOTA_IBS);
    props.getCbs().setAliquota(ALIQUOTA_CBS);
    service = new CalculoIbsCbsService(props);
  }

  // -------------------------------------------------------------------------
  // Caso 1: alíquota cheia, sem redução
  // -------------------------------------------------------------------------

  /**
   * Item: valorTotal = R$ 1.000,00, percentualReducao = 0.
   * EC 132/2023 + LC 214/2025 (ano-teste 2026):
   *   baseCalculo = 1000.00 * 1.0000 = 1000.00
   *   valorIbs    = 1000.00 * 0.0010 = 1.00
   *   valorCbs    = 1000.00 * 0.0090 = 9.00
   */
  @Test
  @DisplayName("deve calcular IBS e CBS sem reducao (aliquota cheia) — LC 214/2025 ano-teste 2026")
  void shouldCalculateFullRateWithNoReduction() {
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), BigDecimal.ZERO);

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getBaseCalculoIbsCbs()).isEqualByComparingTo("1000.00");
    assertThat(item.getAliquotaIbs()).isEqualByComparingTo(ALIQUOTA_IBS);
    assertThat(item.getValorIbs()).isEqualByComparingTo("1.00");
    assertThat(item.getAliquotaCbs()).isEqualByComparingTo(ALIQUOTA_CBS);
    assertThat(item.getValorCbs()).isEqualByComparingTo("9.00");

    assertThat(nota.getValorTotalIbs()).isEqualByComparingTo("1.00");
    assertThat(nota.getValorTotalCbs()).isEqualByComparingTo("9.00");
  }

  // -------------------------------------------------------------------------
  // Caso 2: com redução de 60 % na base
  // -------------------------------------------------------------------------

  /**
   * Redução hipotética de 60 % para produtos agropecuários (LC 214/2025 prevê
   * regimes diferenciados; percentual exato por produto será regulamentado).
   * Item: valorTotal = R$ 1.000,00, percentualReducao = 60.
   *   baseCalculo = 1000.00 * (1 - 0.60) = 1000.00 * 0.40 = 400.00
   *   valorIbs    = 400.00 * 0.0010 = 0.40
   *   valorCbs    = 400.00 * 0.0090 = 3.60
   */
  @Test
  @DisplayName("deve reduzir base de calculo em 60% (regime diferenciado agropecuario) — LC 214/2025")
  void shouldApply60PercentReductionToBase() {
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), new BigDecimal("60"));

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getBaseCalculoIbsCbs()).isEqualByComparingTo("400.00");
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.40");
    assertThat(item.getValorCbs()).isEqualByComparingTo("3.60");

    assertThat(nota.getValorTotalIbs()).isEqualByComparingTo("0.40");
    assertThat(nota.getValorTotalCbs()).isEqualByComparingTo("3.60");
  }

  // -------------------------------------------------------------------------
  // Caso 3: arredondamento HALF_UP a 2 casas decimais
  // -------------------------------------------------------------------------

  /**
   * Escolhido um valorTotal que gere fração na 3.ª casa decimal para cada tributo.
   * Item: valorTotal = R$ 333,33, percentualReducao = 0.
   *   baseCalculo = 333.33
   *   valorIbs    = 333.33 * 0.0010 = 0.33333 → arredonda HALF_UP → 0.33
   *   valorCbs    = 333.33 * 0.0090 = 2.99997 → arredonda HALF_UP → 3.00
   */
  @Test
  @DisplayName("deve aplicar arredondamento HALF_UP com 2 casas decimais — LC 214/2025, art. 12")
  void shouldApplyHalfUpRoundingToTwoDecimalPlaces() {
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("333.33"), BigDecimal.ZERO);

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.33");
    assertThat(item.getValorCbs()).isEqualByComparingTo("3.00");
  }

  // -------------------------------------------------------------------------
  // Caso 4: item de valor zero
  // -------------------------------------------------------------------------

  /**
   * Item com valorTotal = R$ 0,00 (ex.: bonificação sem valor fiscal).
   * Base e tributos devem ser zero.
   */
  @Test
  @DisplayName("deve retornar IBS e CBS zero para item de valor zero")
  void shouldReturnZeroForZeroValueItem() {
    NotaFiscal nota = buildNotaComUmItem(BigDecimal.ZERO, BigDecimal.ZERO);

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getBaseCalculoIbsCbs()).isEqualByComparingTo("0.00");
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.00");
    assertThat(item.getValorCbs()).isEqualByComparingTo("0.00");

    assertThat(nota.getValorTotalIbs()).isEqualByComparingTo("0.00");
    assertThat(nota.getValorTotalCbs()).isEqualByComparingTo("0.00");
  }

  // -------------------------------------------------------------------------
  // Caso 5: múltiplos itens — agregação de totais
  // -------------------------------------------------------------------------

  /**
   * Dois itens:
   *   Item 1: valorTotal = R$ 500,00, sem redução → IBS = 0.50, CBS = 4.50
   *   Item 2: valorTotal = R$ 200,00, sem redução → IBS = 0.20, CBS = 1.80
   *   Total IBS = 0.70, Total CBS = 6.30
   */
  @Test
  @DisplayName("deve agregar valorTotalIbs e valorTotalCbs de multiplos itens")
  void shouldAggregateTotalsForMultipleItems() {
    NotaFiscal nota = buildNotaVazia();

    NotaFiscalItem item1 = buildItem(new BigDecimal("500.00"), BigDecimal.ZERO);
    NotaFiscalItem item2 = buildItem(new BigDecimal("200.00"), BigDecimal.ZERO);
    nota.addItem(item1);
    nota.addItem(item2);

    service.calcular(nota);

    // Item 1: IBS = 500 * 0.0010 = 0.50 ; CBS = 500 * 0.0090 = 4.50
    assertThat(item1.getValorIbs()).isEqualByComparingTo("0.50");
    assertThat(item1.getValorCbs()).isEqualByComparingTo("4.50");

    // Item 2: IBS = 200 * 0.0010 = 0.20 ; CBS = 200 * 0.0090 = 1.80
    assertThat(item2.getValorIbs()).isEqualByComparingTo("0.20");
    assertThat(item2.getValorCbs()).isEqualByComparingTo("1.80");

    // Agregação na nota: IBS = 0.70 ; CBS = 6.30
    assertThat(nota.getValorTotalIbs()).isEqualByComparingTo("0.70");
    assertThat(nota.getValorTotalCbs()).isEqualByComparingTo("6.30");
  }

  // -------------------------------------------------------------------------
  // Caso extra: percentualReducao nulo deve ser tratado como zero
  // -------------------------------------------------------------------------

  /**
   * Item sem percentualReducao informado (null).
   * Deve ser equivalente a percentualReducao = 0.
   * Item: valorTotal = R$ 1.000,00 → IBS = 1.00, CBS = 9.00.
   */
  @Test
  @DisplayName("deve tratar percentualReducao nulo como zero (sem reducao)")
  void shouldTreatNullReductionAsZero() {
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), null);

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getBaseCalculoIbsCbs()).isEqualByComparingTo("1000.00");
    assertThat(item.getValorIbs()).isEqualByComparingTo("1.00");
    assertThat(item.getValorCbs()).isEqualByComparingTo("9.00");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private NotaFiscal buildNotaVazia() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("Produtor Teste")
        .cpfCnpj("12345678901")
        .build();

    return NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Comprador Teste")
        .destinatarioCpfCnpj("98765432100")
        .valorTotalProdutos(BigDecimal.ZERO)
        .valorTotalNota(BigDecimal.ZERO)
        .status(StatusNotaFiscal.RASCUNHO)
        .build();
  }

  private NotaFiscal buildNotaComUmItem(BigDecimal valorTotal, BigDecimal percentualReducao) {
    NotaFiscal nota = buildNotaVazia();
    nota.addItem(buildItem(valorTotal, percentualReducao));
    return nota;
  }

  private NotaFiscalItem buildItem(BigDecimal valorTotal, BigDecimal percentualReducao) {
    return NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .descricao("Produto Teste")
        .ncm("12010090")
        .cfop("6101")
        .unidade("KG")
        .quantidade(BigDecimal.ONE)
        .valorUnitario(valorTotal)
        .valorTotal(valorTotal)
        .percentualReducao(percentualReducao)
        .build();
  }
}
