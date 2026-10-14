package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.RetencaoService.Adquirente;
import br.com.agrocontabil.service.RetencaoService.RetencaoInfo;
import br.com.agrocontabil.service.RetencaoService.SituacaoFunrural;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Regra da RN009 (issue #145): Funrural retido pelo adquirente e split payment, por nota. */
class RetencaoServiceTest {

  private final RetencaoService service = new RetencaoService(
      mock(NotaFiscalRepository.class), mock(ProdutorRepository.class));

  private static NotaFiscal venda(TipoProdutor tipo, String documento, String uf,
      String valorProdutos) {
    return NotaFiscal.builder()
        .produtor(Produtor.builder().tipoProdutor(tipo).build())
        .origem(OrigemNotaFiscal.PROPRIA)
        .status(StatusNotaFiscal.AUTORIZADA)
        .destinatarioCpfCnpj(documento)
        .destinatarioEndereco(EnderecoDestinatario.builder().uf(uf).build())
        .valorTotalProdutos(new BigDecimal(valorProdutos))
        .valorTotalIbs(new BigDecimal("144.00"))
        .valorTotalCbs(new BigDecimal("1296.00"))
        .build();
  }

  @Test
  @DisplayName("Produtor PF vendendo a empresa ou cooperativa: 1,5% retido pelo comprador")
  void pfParaPjRetido() {
    RetencaoInfo r = service.detectarRetencao(
        venda(TipoProdutor.PESSOA_FISICA, "11444777000161", "PR", "144000.00")).orElseThrow();

    assertThat(r.adquirente()).isEqualTo(Adquirente.PESSOA_JURIDICA);
    assertThat(r.funrural().situacao()).isEqualTo(SituacaoFunrural.RETIDO_PELO_ADQUIRENTE);
    assertThat(r.funrural().previdencia()).isEqualByComparingTo("1728.00");
    assertThat(r.funrural().rat()).isEqualByComparingTo("144.00");
    assertThat(r.funrural().senar()).isEqualByComparingTo("288.00");
    assertThat(r.funrural().total()).isEqualByComparingTo("2160.00");
    assertThat(r.splitPayment()).isEqualByComparingTo("1440.00");
  }

  @Test
  @DisplayName("Produtor PF vendendo a PF: ele recolhe; ao exterior: imune, sem Funrural")
  void pfParaPfOuExteriorSemRetencao() {
    RetencaoInfo aPf = service.detectarRetencao(
        venda(TipoProdutor.PESSOA_FISICA, "52998224725", "PR", "1000.00")).orElseThrow();
    RetencaoInfo exterior = service.detectarRetencao(
        venda(TipoProdutor.PESSOA_FISICA, "11444777000161", "EX", "1000.00")).orElseThrow();

    assertThat(aPf.adquirente()).isEqualTo(Adquirente.PESSOA_FISICA);
    assertThat(aPf.funrural().situacao()).isEqualTo(SituacaoFunrural.RECOLHIDO_PELO_PRODUTOR);
    assertThat(aPf.funrural().total()).isEqualByComparingTo("15.00");
    // Exterior pela UF, mesmo com CNPJ preenchido.
    assertThat(exterior.adquirente()).isEqualTo(Adquirente.EXTERIOR);
    assertThat(exterior.funrural().situacao()).isEqualTo(SituacaoFunrural.EXPORTACAO_IMUNE);
    assertThat(exterior.funrural().total()).isZero();
    // Exportacao nao paga Funrural, mas o IBS/CBS destacado segue para o split payment.
    assertThat(exterior.splitPayment()).isEqualByComparingTo("1440.00");
  }

  @Test
  @DisplayName("Produtor PJ ou sem tipo cadastrado: Funrural fora da regra, split payment segue")
  void produtorPjNaoTemRetencao() {
    for (TipoProdutor tipo : new TipoProdutor[] {TipoProdutor.PESSOA_JURIDICA, null}) {
      RetencaoInfo r = service.detectarRetencao(
          venda(tipo, "11444777000161", "PR", "1000.00")).orElseThrow();

      assertThat(r.funrural().situacao()).isEqualTo(SituacaoFunrural.NAO_SE_APLICA);
      assertThat(r.funrural().total()).isZero();
      assertThat(r.splitPayment()).isEqualByComparingTo("1440.00");
    }
  }

  @ParameterizedTest
  @EnumSource(value = StatusNotaFiscal.class, names = "AUTORIZADA", mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Venda não autorizada não tem retenção")
  void soVendaAutorizada(StatusNotaFiscal status) {
    NotaFiscal nota = venda(TipoProdutor.PESSOA_FISICA, "11444777000161", "PR", "1000.00");
    nota.setStatus(status);

    assertThat(service.detectarRetencao(nota)).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("Compra (nota importada) não tem retenção para o produtor")
  void compraNaoTemRetencao() {
    NotaFiscal nota = venda(TipoProdutor.PESSOA_FISICA, "11444777000161", "PR", "1000.00");
    nota.setOrigem(OrigemNotaFiscal.IMPORTADA);

    assertThat(service.detectarRetencao(nota)).isEmpty();
  }

  @Test
  @DisplayName("Arredondamento por parcela, meio para cima")
  void arredondamento() {
    // 0,1% de 1.234,55 = 1,23455 → 1,23; 0,2% = 2,4691 → 2,47; 1,2% = 14,8146 → 14,81
    RetencaoInfo r = service.detectarRetencao(
        venda(TipoProdutor.PESSOA_FISICA, "11444777000161", "PR", "1234.55")).orElseThrow();

    assertThat(r.funrural().rat()).isEqualByComparingTo("1.23");
    assertThat(r.funrural().senar()).isEqualByComparingTo("2.47");
    assertThat(r.funrural().previdencia()).isEqualByComparingTo("14.81");
  }
}
