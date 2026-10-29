package br.com.agrocontabil.service;

import br.com.agrocontabil.repository.AliquotaCbsRepository;
import br.com.agrocontabil.support.ReducaoTestSupport;
import java.util.List;
import java.time.LocalDate;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NaturezaOperacaoTributaria;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link TributacaoService} (Sprint 4 — issue Agrocontabil#30, RF015).
 *
 * <p>Referência legal para todos os casos:
 * <ul>
 *   <li><strong>EC 132/2023</strong> — institui IBS e CBS.</li>
 *   <li><strong>LC 214/2025</strong> — regulamenta o cálculo (arts. 12–18 — base; 87–92 —
 *       alíquotas). Para 2026 (ano-teste): CBS 0,90 % e IBS 0,10 % (referência técnica).</li>
 * </ul>
 *
 * <p>Fórmulas e regras testadas:
 * <pre>
 *   reducao     = percentualReducao do item, OU redução padrão do NCM quando o item omite
 *   baseCalculo = valorTotal * (1 - reducao / 100)
 *   valorIbs    = baseCalculo * aliquotaIbs    — HALF_UP 2 casas
 *   valorCbs    = baseCalculo * aliquotaCbs    — HALF_UP 2 casas
 *   natureza    = DEBITO (CFOP 5/6/7 = saída) | CREDITO (CFOP 1/2/3 = entrada)
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class TributacaoServiceTest {

  private static final BigDecimal ALIQUOTA_IBS = new BigDecimal("0.0010");
  private static final BigDecimal ALIQUOTA_CBS = new BigDecimal("0.0090");

  @Mock private AliquotaIbsUfRepository aliquotaIbsUfRepository;
  @Mock private AliquotaCbsRepository aliquotaCbsRepository;

  private TributacaoService service;
  private ClassificacaoTributariaService classificacao;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    props.getIbs().setAliquota(ALIQUOTA_IBS);
    props.getCbs().setAliquota(ALIQUOTA_CBS);
    // Por padrão nenhum NCM tem redução na tabela oficial; casos específicos sobrescrevem.
    classificacao = ReducaoTestSupport.classificacaoSemReducao();
    service = new TributacaoService(
        new ReducaoNcmResolver(classificacao),
        new AliquotaIbsCbsResolver(aliquotaIbsUfRepository, aliquotaCbsRepository, props));
    // Por padrão nenhuma UF de referência (notas sem endereço caem no fallback das properties);
    // casos específicos sobrescrevem por UF.
    lenient()
        .when(aliquotaIbsUfRepository.findVigente(anyString(), any(LocalDate.class)))
        .thenReturn(List.of());
    // Sem vigência de CBS por padrão: cai no fallback das properties (issue #85).
    lenient().when(aliquotaCbsRepository.findVigente(any(LocalDate.class))).thenReturn(List.of());
  }

  // -------------------------------------------------------------------------
  // Cálculo IBS/CBS — alíquota cheia, sem redução
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve calcular IBS e CBS sem reducao (aliquota cheia) — LC 214/2025 ano-teste 2026")
  void shouldCalculateFullRateWithNoReduction() {
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), BigDecimal.ZERO, "6101");

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
  // Redução informada no item tem precedência
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve reduzir base em 60% quando o item informa percentualReducao — LC 214/2025")
  void shouldApplyItemReductionWhenProvided() {
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), new BigDecimal("60"), "6101");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getBaseCalculoIbsCbs()).isEqualByComparingTo("400.00");
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.40");
    assertThat(item.getValorCbs()).isEqualByComparingTo("3.60");
  }

  // -------------------------------------------------------------------------
  // Redução por NCM aplicada como default quando o item omite percentualReducao
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve aplicar reducao padrao do NCM quando o item nao informa percentualReducao")
  void shouldApplyNcmDefaultReductionWhenItemOmitsReduction() {
    ReducaoTestSupport.comReducao(classificacao, "12019000", "40", "200036");
    // percentualReducao nulo no item -> usa o default do NCM (40%)
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), null, "6101");
    nota.getItens().get(0).setNcm("12019000");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    // base = 1000 * (1 - 0.40) = 600.00
    assertThat(item.getBaseCalculoIbsCbs()).isEqualByComparingTo("600.00");
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.60");
    assertThat(item.getValorCbs()).isEqualByComparingTo("5.40");
  }

  @Test
  @DisplayName("percentualReducao explicito do item deve prevalecer sobre a reducao padrao do NCM")
  void itemReductionShouldOverrideNcmDefault() {
    // NCM traz 40% de default, mas o item informa 0% explicitamente -> vence o item (0%).
    ReducaoTestSupport.comReducao(classificacao, "12019000", "40", "200036");
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), BigDecimal.ZERO, "6101");
    nota.getItens().get(0).setNcm("12019000");

    service.calcular(nota);

    // base cheia porque o item explicitou 0% (nao caiu no default do NCM)
    assertThat(nota.getItens().get(0).getBaseCalculoIbsCbs()).isEqualByComparingTo("1000.00");
  }

  @Test
  @DisplayName("NCM sem reducao de referencia deve resultar em base cheia (sem reducao)")
  void unknownNcmShouldResultInFullBase() {
    // tabela simulada sem reducao para o NCM (default do setUp)
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("1000.00"), null, "6101");
    nota.getItens().get(0).setNcm("99999999");

    service.calcular(nota);

    assertThat(nota.getItens().get(0).getBaseCalculoIbsCbs()).isEqualByComparingTo("1000.00");
    assertThat(nota.getItens().get(0).getValorIbs()).isEqualByComparingTo("1.00");
  }

  // -------------------------------------------------------------------------
  // Arredondamento HALF_UP
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve aplicar arredondamento HALF_UP com 2 casas decimais — LC 214/2025, art. 12")
  void shouldApplyHalfUpRoundingToTwoDecimalPlaces() {
    NotaFiscal nota = buildNotaComUmItem(new BigDecimal("333.33"), BigDecimal.ZERO, "6101");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.33");
    assertThat(item.getValorCbs()).isEqualByComparingTo("3.00");
  }

  // -------------------------------------------------------------------------
  // Item de valor zero
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve retornar IBS e CBS zero para item de valor zero")
  void shouldReturnZeroForZeroValueItem() {
    NotaFiscal nota = buildNotaComUmItem(BigDecimal.ZERO, BigDecimal.ZERO, "6101");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getBaseCalculoIbsCbs()).isEqualByComparingTo("0.00");
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.00");
    assertThat(item.getValorCbs()).isEqualByComparingTo("0.00");
    assertThat(nota.getValorTotalIbs()).isEqualByComparingTo("0.00");
    assertThat(nota.getValorTotalCbs()).isEqualByComparingTo("0.00");
  }

  // -------------------------------------------------------------------------
  // Agregação de múltiplos itens
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("deve agregar valorTotalIbs e valorTotalCbs de multiplos itens")
  void shouldAggregateTotalsForMultipleItems() {
    NotaFiscal nota = buildNotaVazia();
    nota.addItem(buildItem(new BigDecimal("500.00"), BigDecimal.ZERO, "6101"));
    nota.addItem(buildItem(new BigDecimal("200.00"), BigDecimal.ZERO, "6101"));

    service.calcular(nota);

    assertThat(nota.getItens().get(0).getValorIbs()).isEqualByComparingTo("0.50");
    assertThat(nota.getItens().get(1).getValorIbs()).isEqualByComparingTo("0.20");
    assertThat(nota.getValorTotalIbs()).isEqualByComparingTo("0.70");
    assertThat(nota.getValorTotalCbs()).isEqualByComparingTo("6.30");
  }

  // -------------------------------------------------------------------------
  // Classificação da operação por CFOP (natureza tributária)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("CFOP de saida (5/6/7) deve classificar a natureza como DEBITO")
  void saidaCfopShouldClassifyAsDebito() {
    NotaFiscal nota = buildNotaVazia();
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, "5101"));
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, "6101"));
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, "7101"));

    service.calcular(nota);

    assertThat(nota.getItens())
        .allMatch(i -> i.getNaturezaOperacao() == NaturezaOperacaoTributaria.DEBITO);
  }

  @Test
  @DisplayName("CFOP de entrada (1/2/3) deve classificar a natureza como CREDITO")
  void entradaCfopShouldClassifyAsCredito() {
    NotaFiscal nota = buildNotaVazia();
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, "1101"));
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, "2101"));
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, "3101"));

    service.calcular(nota);

    assertThat(nota.getItens())
        .allMatch(i -> i.getNaturezaOperacao() == NaturezaOperacaoTributaria.CREDITO);
  }

  @Test
  @DisplayName("CFOP nulo ou fora do padrao deve resultar em natureza nula (conservador)")
  void invalidCfopShouldResultInNullNatureza() {
    NotaFiscal nota = buildNotaVazia();
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, null));
    nota.addItem(buildItem(new BigDecimal("100.00"), BigDecimal.ZERO, "9999"));

    service.calcular(nota);

    assertThat(nota.getItens()).allMatch(i -> i.getNaturezaOperacao() == null);
  }

  // -------------------------------------------------------------------------
  // Alíquota de IBS por UF (RN002, issue #31) — resolução interestadual/intraestadual
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("operacao interestadual deve usar a aliquota de IBS da UF de DESTINO — RN002")
  void interestadualShouldUseDestinationUfRate() {
    // Emitente em SP; destino em MG com IBS diferenciado (UF = MG, 0,03% estadual + 0,01% mun).
    when(aliquotaIbsUfRepository.findVigente(eq("MG"), any(LocalDate.class)))
        .thenReturn(List.of(aliquota("MG", "0.0003", "0.0001")));

    NotaFiscal nota = buildNota("SP", "MG", new BigDecimal("1000.00"), "6101");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    // Alíquota total de IBS = 0.0003 + 0.0001 = 0.0004 (da UF de destino, MG).
    assertThat(item.getAliquotaIbs()).isEqualByComparingTo("0.0004");
    assertThat(item.getAliquotaIbsUf()).isEqualByComparingTo("0.0003");
    assertThat(item.getAliquotaIbsMun()).isEqualByComparingTo("0.0001");
    // valorIbs = 1000 * 0.0004 = 0.40; parcelas: UF = 1000*0.0003 = 0.30; Mun = 0.10.
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.40");
    assertThat(item.getValorIbsUf()).isEqualByComparingTo("0.30");
    assertThat(item.getValorIbsMun()).isEqualByComparingTo("0.10");
  }

  @Test
  @DisplayName("operacao intraestadual deve usar a aliquota de IBS da UF do EMITENTE — RN002")
  void intraestadualShouldUseEmitenteUfRate() {
    // Emitente e destino em SP; SP com IBS 0,0006 estadual + 0,0002 municipal.
    when(aliquotaIbsUfRepository.findVigente(eq("SP"), any(LocalDate.class)))
        .thenReturn(List.of(aliquota("SP", "0.0006", "0.0002")));

    NotaFiscal nota = buildNota("SP", "SP", new BigDecimal("1000.00"), "5101");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getAliquotaIbs()).isEqualByComparingTo("0.0008");
    assertThat(item.getAliquotaIbsUf()).isEqualByComparingTo("0.0006");
    assertThat(item.getAliquotaIbsMun()).isEqualByComparingTo("0.0002");
    // valorIbs = 1000 * 0.0008 = 0.80; UF = 0.60; Mun = 0.20.
    assertThat(item.getValorIbs()).isEqualByComparingTo("0.80");
    assertThat(item.getValorIbsUf()).isEqualByComparingTo("0.60");
    assertThat(item.getValorIbsMun()).isEqualByComparingTo("0.20");
  }

  @Test
  @DisplayName("split estadual/municipal deve somar exatamente o valorIbs (sem perda no arredondamento)")
  void splitShouldSumToTotalIbs() {
    // Base impar para forcar arredondamento: 333.33 * 0.0005 = 0.166665 -> parcelas de centavo.
    when(aliquotaIbsUfRepository.findVigente(eq("MG"), any(LocalDate.class)))
        .thenReturn(List.of(aliquota("MG", "0.0005", "0.0005")));

    NotaFiscal nota = buildNota("SP", "MG", new BigDecimal("333.33"), "6101");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    assertThat(item.getValorIbsUf().add(item.getValorIbsMun()))
        .isEqualByComparingTo(item.getValorIbs());
  }

  @Test
  @DisplayName("UF fora da tabela de referencia deve cair no fallback da aliquota parametrizada")
  void unknownUfShouldFallBackToConfiguredRate() {
    // findVigente devolve vazio (default do setUp) para uma UF nao mapeada.
    NotaFiscal nota = buildNota("SP", "MG", new BigDecimal("1000.00"), "6101");

    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    // Fallback: aliquota cheia das properties (0.0010), dividida igualmente entre UF e municipio.
    assertThat(item.getAliquotaIbs()).isEqualByComparingTo(ALIQUOTA_IBS);
    assertThat(item.getValorIbs()).isEqualByComparingTo("1.00");
    assertThat(item.getValorIbsUf().add(item.getValorIbsMun()))
        .isEqualByComparingTo(item.getValorIbs());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private AliquotaIbsUf aliquota(String uf, String estadual, String municipal) {
    return AliquotaIbsUf.builder()
        .uf(uf)
        .aliquotaEstadual(new BigDecimal(estadual))
        .aliquotaMunicipal(new BigDecimal(municipal))
        .build();
  }

  private NotaFiscal buildNota(
      String ufEmitente, String ufDestino, BigDecimal valorTotal, String cfop) {
    NotaFiscal nota = buildNotaVazia();
    nota.getProdutor().setEstado(ufEmitente);
    nota.setDestinatarioEndereco(
        EnderecoDestinatario.builder().uf(ufDestino).municipio("Municipio").build());
    nota.addItem(buildItem(valorTotal, BigDecimal.ZERO, cfop));
    return nota;
  }

  private NotaFiscal buildNotaVazia() {
    Produtor produtor =
        Produtor.builder()
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

  private NotaFiscal buildNotaComUmItem(
      BigDecimal valorTotal, BigDecimal percentualReducao, String cfop) {
    NotaFiscal nota = buildNotaVazia();
    nota.addItem(buildItem(valorTotal, percentualReducao, cfop));
    return nota;
  }

  private NotaFiscalItem buildItem(BigDecimal valorTotal, BigDecimal percentualReducao, String cfop) {
    return NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .descricao("Produto Teste")
        .ncm("12019000")
        .cfop(cfop)
        .unidade("KG")
        .quantidade(BigDecimal.ONE)
        .valorUnitario(valorTotal)
        .valorTotal(valorTotal)
        .percentualReducao(percentualReducao)
        .build();
  }
}
