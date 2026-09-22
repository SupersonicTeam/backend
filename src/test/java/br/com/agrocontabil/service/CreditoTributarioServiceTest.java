package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link CreditoTributarioService} (issue Agrocontabil#30 — RN003; correção de
 * simetria débito × crédito — issue Agrocontabil#52).
 *
 * <p>Usa {@link TributosProperties} reais com as alíquotas de referência do ano-teste 2026
 * (IBS 0,10 %, CBS 0,90 %) e aproveitamento integral (100 %), sobrescritos por teste quando o
 * cenário exige. A redução por NCM é resolvida pelo {@link ReducaoNcmResolver} sobre um
 * {@link NcmReferenciaRepository} mockado que, por padrão, não devolve redução para nenhum NCM.
 */
class CreditoTributarioServiceTest {

  private TributosProperties tributosProperties;
  private NcmReferenciaRepository ncmReferenciaRepository;
  private CreditoTributarioService service;

  @BeforeEach
  void setUp() {
    tributosProperties = new TributosProperties();
    ncmReferenciaRepository = mock(NcmReferenciaRepository.class);
    // Por padrão nenhum NCM tem redução de referência; casos específicos sobrescrevem.
    lenient()
        .when(ncmReferenciaRepository.findPercentualReducaoPadraoByCodigo(anyString()))
        .thenReturn(Optional.empty());
    service = new CreditoTributarioService(
        tributosProperties, new ReducaoNcmResolver(ncmReferenciaRepository));
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

  @Test
  @DisplayName("Recalculo zera credito de item que deixou de ser elegivel (idempotencia)")
  void recalculoZeraCreditoDeItemNaoMaisElegivel() {
    NotaFiscalItem item = item("7101", "1000.00");
    // Simula estado antigo: item ja tinha credito preenchido por um calculo anterior.
    item.setValorCreditoIbs(new BigDecimal("1.00"));
    item.setValorCreditoCbs(new BigDecimal("9.00"));
    NotaFiscal nota = notaImportada(item);

    service.calcular(nota);

    // CFOP 7101 (exportacao) nao e elegivel: credito deve ser limpo, nao mantido.
    assertThat(item.getValorCreditoIbs()).isNull();
    assertThat(item.getValorCreditoCbs()).isNull();
    assertThat(nota.getValorTotalCreditoIbs()).isEqualByComparingTo("0.00");
    assertThat(nota.getValorTotalCreditoCbs()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("Credito aplica a reducao padrao do NCM quando o item omite percentualReducao (#52)")
  void creditoAplicaReducaoPadraoPorNcm() {
    // NCM com reducao padrao de 60% na tabela de referencia; item nao informa percentualReducao.
    when(ncmReferenciaRepository.findPercentualReducaoPadraoByCodigo("12010090"))
        .thenReturn(Optional.of(new BigDecimal("60")));
    NotaFiscalItem item = item("6101", "1000.00");
    item.setNcm("12010090");
    NotaFiscal nota = notaImportada(item);

    service.calcular(nota);

    // base = 1000 × (1 − 0,60) = 400.00 → IBS 0.40; CBS 3.60 — simetrico ao debito (issue #52).
    assertThat(nota.getItens().get(0).getValorCreditoIbs()).isEqualByComparingTo("0.40");
    assertThat(nota.getItens().get(0).getValorCreditoCbs()).isEqualByComparingTo("3.60");
  }

  @Test
  @DisplayName("percentualReducao informado no item prevalece sobre a reducao padrao do NCM (#52)")
  void percentualReducaoDoItemPrevaleceSobreNcm() {
    // Mesmo com o NCM tendo reducao padrao de 30%, o valor informado no item (60%) prevalece.
    when(ncmReferenciaRepository.findPercentualReducaoPadraoByCodigo("12010090"))
        .thenReturn(Optional.of(new BigDecimal("30")));
    NotaFiscalItem item = item("6101", "1000.00");
    item.setNcm("12010090");
    item.setPercentualReducao(new BigDecimal("60"));
    NotaFiscal nota = notaImportada(item);

    service.calcular(nota);

    // base = 1000 × (1 − 0,60) = 400.00 → IBS 0.40; CBS 3.60 (usa 60% do item, nao 30% do NCM).
    assertThat(nota.getItens().get(0).getValorCreditoIbs()).isEqualByComparingTo("0.40");
    assertThat(nota.getItens().get(0).getValorCreditoCbs()).isEqualByComparingTo("3.60");
  }

  @Test
  @DisplayName("Credito usa a mesma aliquota de IBS por UF que o debito, nao a fixa do yml (#86)")
  void creditoUsaAliquotaPorUfDivergenteDoYml() {
    // Debito: a UF aplicavel (MT) tem aliquota 0,0030 + 0,0020 = 0,0050 na tabela aliquota_ibs_uf,
    // divergente da aliquota fixa do yml (0,0010). O seed real e uniforme e mascara a diferenca.
    AliquotaIbsUfRepository aliquotaIbsUfRepository = mock(AliquotaIbsUfRepository.class);
    when(aliquotaIbsUfRepository.findByUf("MT")).thenReturn(Optional.of(
        AliquotaIbsUf.builder()
            .uf("MT")
            .aliquotaEstadual(new BigDecimal("0.0030"))
            .aliquotaMunicipal(new BigDecimal("0.0020"))
            .build()));
    TributacaoService debito = new TributacaoService(
        tributosProperties,
        new ReducaoNcmResolver(ncmReferenciaRepository),
        aliquotaIbsUfRepository);

    NotaFiscal nota = notaImportada(item("6101", "1000.00"));
    nota.setProdutor(Produtor.builder().estado("MT").build());
    nota.setDestinatarioEndereco(EnderecoDestinatario.builder().uf("MT").build());

    debito.calcular(nota);
    service.calcular(nota);

    NotaFiscalItem item = nota.getItens().get(0);
    // Simetria (RN002): o credito deve ser base × aliquota efetivamente aplicada no debito.
    BigDecimal esperado = item.getBaseCalculoIbsCbs()
        .multiply(item.getAliquotaIbs())
        .setScale(2, java.math.RoundingMode.HALF_UP);
    assertThat(item.getAliquotaIbs()).isEqualByComparingTo("0.0050");
    assertThat(item.getValorCreditoIbs()).isEqualByComparingTo(esperado); // 5.00, nao 1.00
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
