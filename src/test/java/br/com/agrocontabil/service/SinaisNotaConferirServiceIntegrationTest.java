package br.com.agrocontabil.service;

import static br.com.agrocontabil.support.DadosCeresFixture.saida;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.SinaisNotaConferirService.NotaParaSinal;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import br.com.agrocontabil.service.SinalNota.Motivo;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Sinais de duplicidade e de valor fora do padrão contra o banco (issue #276, PR 2): as consultas
 * só olham as notas do próprio produtor, e canceladas não contam.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class SinaisNotaConferirServiceIntegrationTest {

  private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

  @Autowired private SinaisNotaConferirService service;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtorA;
  private Produtor produtorB;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtorA = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
    produtorB = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda B").cpfCnpj("11122233344").estado("PR").build());
  }

  @Test
  @DisplayName("valor unitario 1000x a mediana de 5 vendas do mesmo NCM e unidade: ACIMA_DO_PADRAO")
  void valorForaDoPadrao() {
    for (int i = 1; i <= 5; i++) {
      notaFiscalRepository.save(saida(produtorA, diasAtras(10 * i), "Milho em grao", "10059010",
          "145.00"));
    }
    // De outro produtor, com outro preco: nunca entra na mediana de A.
    for (int i = 1; i <= 5; i++) {
      notaFiscalRepository.save(saida(produtorB, diasAtras(10 * i), "Milho em grao", "10059010",
          "145000.00"));
    }
    NotaFiscal errada = rascunho(saida(produtorA, diasAtras(1), "Milho em grao", "10059010",
        "145000.00"));

    List<SinalNota> sinais = service.sinais(produtorA.getId(), paraSinal(errada));

    assertThat(sinais).singleElement().satisfies(s -> {
      assertThat(s.codigo()).isEqualTo(CodigoSinalNota.VALOR_UNITARIO_FORA_PADRAO);
      assertThat(s.motivo()).isEqualTo(Motivo.ACIMA_DO_PADRAO);
      assertThat(s.valor()).isEqualByComparingTo("1450");
      assertThat(s.referencia()).isEqualByComparingTo("1.45");
      assertThat(s.unidade()).isEqualTo("KG");
    });
    // A mesma nota, para B (que tem 5 vendas a 1450): o preco e o de sempre.
    NotaFiscal deB = rascunho(saida(produtorB, diasAtras(1), "Milho em grao", "10059010",
        "145000.00"));
    assertThat(service.sinais(produtorB.getId(), paraSinal(deB))).isEmpty();
  }

  @Test
  @DisplayName("com 4 notas de referencia nao ha mediana; cancelada nao conta")
  void poucasNotas() {
    for (int i = 1; i <= 4; i++) {
      notaFiscalRepository.save(saida(produtorA, diasAtras(10 * i), "Milho", "10059010",
          "145.00"));
    }
    NotaFiscal cancelada = saida(produtorA, diasAtras(60), "Milho", "10059010", "145.00");
    cancelada.setStatus(StatusNotaFiscal.CANCELADA);
    notaFiscalRepository.save(cancelada);
    NotaFiscal errada = rascunho(saida(produtorA, diasAtras(1), "Milho", "10059010",
        "145000.00"));

    assertThat(service.sinais(produtorA.getId(), paraSinal(errada))).isEmpty();
  }

  @Test
  @DisplayName("mesma venda no mesmo dia: duplicidade com o numero da outra; de B e cancelada, nao")
  void duplicidade() {
    OffsetDateTime dia = diasAtras(2);
    NotaFiscal primeira = notaFiscalRepository.save(saida(produtorA, dia, "Soja", "12019000",
        "5000.00"));
    NotaFiscal cancelada = saida(produtorA, dia.plusHours(1), "Soja", "12019000", "5000.00");
    cancelada.setStatus(StatusNotaFiscal.CANCELADA);
    notaFiscalRepository.save(cancelada);
    notaFiscalRepository.save(saida(produtorB, dia.plusHours(2), "Soja", "12019000", "5000.00"));
    NotaFiscal segunda = rascunho(saida(produtorA, dia.plusHours(3), "Soja", "12019000",
        "5000.00"));

    assertThat(service.sinais(produtorA.getId(), paraSinal(segunda)))
        .filteredOn(s -> s.codigo() == CodigoSinalNota.POSSIVEL_DUPLICIDADE)
        .singleElement()
        .satisfies(s -> assertThat(s.outraNota()).isEqualTo(primeira.getNumero()));

    // Outro dia, ou outra quantidade: nao e a mesma venda.
    NotaFiscal outroDia = rascunho(saida(produtorA, dia.minusDays(1), "Soja", "12019000",
        "5000.00"));
    assertThat(service.sinais(produtorA.getId(), paraSinal(outroDia)))
        .noneMatch(s -> s.codigo() == CodigoSinalNota.POSSIVEL_DUPLICIDADE);
    NotaFiscal outraQuantidade = saida(produtorA, dia, "Soja", "12019000", "5000.00");
    outraQuantidade.getItens().get(0).setQuantidade(new java.math.BigDecimal("101"));
    outraQuantidade = rascunho(outraQuantidade);
    assertThat(service.sinais(produtorA.getId(), paraSinal(outraQuantidade)))
        .noneMatch(s -> s.codigo() == CodigoSinalNota.POSSIVEL_DUPLICIDADE);
  }

  private NotaFiscal rascunho(NotaFiscal nota) {
    nota.setStatus(StatusNotaFiscal.RASCUNHO);
    return notaFiscalRepository.save(nota);
  }

  private static NotaParaSinal paraSinal(NotaFiscal nota) {
    return new NotaParaSinal(nota.getId(), nota.getOrigem(), nota.getStatus(),
        nota.getDataEmissao());
  }

  private static OffsetDateTime diasAtras(int dias) {
    return OffsetDateTime.now(BRT).truncatedTo(ChronoUnit.DAYS).withHour(10).minusDays(dias);
  }
}
