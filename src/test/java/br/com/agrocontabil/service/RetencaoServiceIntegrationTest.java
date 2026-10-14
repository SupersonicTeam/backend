package br.com.agrocontabil.service;

import static br.com.agrocontabil.support.DadosCeresFixture.em;
import static br.com.agrocontabil.support.DadosCeresFixture.entrada;
import static br.com.agrocontabil.support.DadosCeresFixture.saida;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.RetencaoService.ResumoRetencoes;
import br.com.agrocontabil.service.RetencaoService.SituacaoFunrural;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Retenções do período contra o banco (RN009, issue #145): o recorte por comprador vem da consulta
 * agregada, e só entram vendas próprias autorizadas do produtor no período.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class RetencaoServiceIntegrationTest {

  private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
  private static final LocalDate AGO = LocalDate.of(2026, 8, 31);

  @Autowired private RetencaoService service;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor pf;
  private Produtor outro;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    pf = produtorRepository.save(Produtor.builder().nomeCompleto("Produtor PF")
        .cpfCnpj("11144477735").estado("PR").tipoProdutor(TipoProdutor.PESSOA_FISICA).build());
    outro = produtorRepository.save(Produtor.builder().nomeCompleto("Outro PF")
        .cpfCnpj("52998224725").estado("PR").tipoProdutor(TipoProdutor.PESSOA_FISICA).build());

    // Entram: venda a cooperativa (retida), a pessoa física e ao exterior (sem retenção).
    salvar(venda(pf, em(2026, 3, 25), "11444777000161", "PR", "144000.00",
        StatusNotaFiscal.AUTORIZADA));
    salvar(venda(pf, em(2026, 6, 14), "12345678901", "PR", "10000.00",
        StatusNotaFiscal.AUTORIZADA));
    salvar(venda(pf, em(2026, 7, 2), "00000000000000", "EX", "5000.00",
        StatusNotaFiscal.AUTORIZADA));
    // Nao entram: cancelada, rascunho, fora do periodo, compra, venda de outro produtor.
    salvar(venda(pf, em(2026, 4, 1), "11444777000161", "PR", "70000.00",
        StatusNotaFiscal.CANCELADA));
    salvar(venda(pf, em(2026, 5, 1), "11444777000161", "PR", "9000.00",
        StatusNotaFiscal.RASCUNHO));
    salvar(venda(pf, em(2026, 9, 1), "11444777000161", "PR", "8000.00",
        StatusNotaFiscal.AUTORIZADA));
    salvar(entrada(pf, StatusNotaFiscal.AUTORIZADA, em(2026, 2, 10), "FERTILIZANTE", "31052000",
        "KG", "100", "2500.00"));
    salvar(venda(outro, em(2026, 3, 1), "11444777000161", "PR", "999999.00",
        StatusNotaFiscal.AUTORIZADA));
  }

  private void salvar(NotaFiscal nota) {
    notaFiscalRepository.save(nota);
  }

  private static NotaFiscal venda(Produtor produtor, OffsetDateTime emissao, String documento,
      String uf, String valor, StatusNotaFiscal status) {
    NotaFiscal nota = saida(produtor, emissao, "Soja em graos", "12019000", valor);
    nota.setStatus(status);
    nota.setDestinatarioCpfCnpj(documento);
    nota.setDestinatarioEndereco(EnderecoDestinatario.builder()
        .logradouro("Rua A").numero("1").municipio("Cascavel").codigoMunicipioIbge("4104808")
        .uf(uf).cep("85800000").build());
    BigDecimal base = new BigDecimal(valor);
    nota.setValorTotalIbs(base.multiply(new BigDecimal("0.001")));
    nota.setValorTotalCbs(base.multiply(new BigDecimal("0.009")));
    return nota;
  }

  @Test
  @DisplayName("Funrural retido nas vendas a empresa, recolhido pelo produtor a PF, nada ao exterior")
  void resumoDoPeriodo() {
    ResumoRetencoes r = service.resumir(pf.getId(), JAN, AGO);

    assertThat(r.retidoPeloAdquirente().situacao())
        .isEqualTo(SituacaoFunrural.RETIDO_PELO_ADQUIRENTE);
    assertThat(r.notasRetidas()).isEqualTo(1);
    assertThat(r.retidoPeloAdquirente().base()).isEqualByComparingTo("144000.00");
    assertThat(r.retidoPeloAdquirente().total()).isEqualByComparingTo("2160.00");
    assertThat(r.notasSemRetencao()).isEqualTo(1);
    assertThat(r.recolhidoPeloProdutor().base()).isEqualByComparingTo("10000.00");
    assertThat(r.recolhidoPeloProdutor().total()).isEqualByComparingTo("150.00");
    // Exportacao: imune (Constituicao, art. 149, par. 2, I), base informada e Funrural zero.
    assertThat(r.notasExportacao()).isEqualTo(1);
    assertThat(r.exportacao().situacao()).isEqualTo(SituacaoFunrural.EXPORTACAO_IMUNE);
    assertThat(r.exportacao().base()).isEqualByComparingTo("5000.00");
    assertThat(r.exportacao().total()).isZero();
    assertThat(r.notasVendidas()).isEqualTo(3);
    // Split payment: IBS 0,1% e CBS 0,9% de 159.000,00.
    assertThat(r.ibsSplitPayment()).isEqualByComparingTo("159.00");
    assertThat(r.cbsSplitPayment()).isEqualByComparingTo("1431.00");
  }

  @Test
  @DisplayName("Período sem venda: tudo zero, sem erro")
  void periodoSemVenda() {
    ResumoRetencoes r = service.resumir(pf.getId(), LocalDate.of(2025, 1, 1),
        LocalDate.of(2025, 12, 31));

    assertThat(r.notasVendidas()).isZero();
    assertThat(r.retidoPeloAdquirente().total()).isZero();
    assertThat(r.ibsSplitPayment()).isZero();
  }

  @Test
  @DisplayName("Produtor inexistente é 404, não um resumo vazio")
  void produtorInexistente() {
    assertThatThrownBy(() -> service.resumir(UUID.randomUUID(), JAN, AGO))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
