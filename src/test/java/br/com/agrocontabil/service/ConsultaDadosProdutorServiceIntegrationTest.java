package br.com.agrocontabil.service;

import static br.com.agrocontabil.support.DadosCeresFixture.em;
import static br.com.agrocontabil.support.DadosCeresFixture.entrada;
import static br.com.agrocontabil.support.DadosCeresFixture.guia;
import static br.com.agrocontabil.support.DadosCeresFixture.saida;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaResumo;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.TotalAgregado;
import br.com.agrocontabil.repository.VencimentoGuiaResumo;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.time.LocalDate;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Consultas da Ceres contra o banco (issues #142 e #143): recortes exatos (só entradas autorizadas
 * no período), isolamento entre produtores e curingas do {@code LIKE} tratados como texto.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class ConsultaDadosProdutorServiceIntegrationTest {

  private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
  private static final LocalDate JUN = LocalDate.of(2026, 6, 30);

  @Autowired private ConsultaDadosProdutorService service;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private GuiaFiscalRepository guiaFiscalRepository;
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

    // Contam: entradas autorizadas de A no semestre.
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 2, 10),
        "FERTILIZANTE NPK 04-14-08", "31052000", "KG", "1000", "2500.00"));
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 5, 3),
        "Fertilizante foliar", "31059090", "L", "20", "1200.00"));
    // Nao contam: cancelada, fora do periodo, saida propria, de outro produtor, outro insumo.
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.CANCELADA, em(2026, 3, 1),
        "FERTILIZANTE NPK", "31052000", "KG", "500", "900.00"));
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 7, 1),
        "FERTILIZANTE NPK", "31052000", "KG", "500", "800.00"));
    notaFiscalRepository.save(saida(produtorA, em(2026, 4, 1), "FERTILIZANTE revendido",
        "31052000", "300.00"));
    notaFiscalRepository.save(entrada(produtorB, StatusNotaFiscal.AUTORIZADA, em(2026, 4, 1),
        "FERTILIZANTE NPK", "31052000", "KG", "9999", "99999.00"));
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 4, 2),
        "Glifosato 100% puro", "38089324", "L", "10", "450.00"));
  }

  @Test
  @DisplayName("Compras por descricao: so entradas autorizadas do produtor no periodo")
  void comprasPorDescricaoRecorteExato() {
    ComprasInsumoResultado r = service.comprasPorDescricao(produtorA.getId(), "fertilizante",
        JAN, JUN);

    assertThat(r.valorTotal()).isEqualByComparingTo("3700.00");
    assertThat(r.quantidadeNotas()).isEqualTo(2);
    assertThat(r.porUnidade()).extracting(u -> u.unidade()).containsExactly("KG", "L");
    assertThat(r.porUnidade().get(0).quantidade()).isEqualByComparingTo("1000");
  }

  @Test
  @DisplayName("Isolamento: o produtor B nunca entra na soma do A, e vice-versa")
  void isolamentoEntreProdutores() {
    assertThat(service.comprasPorDescricao(produtorB.getId(), "fertilizante", JAN, JUN)
        .valorTotal()).isEqualByComparingTo("99999.00");
    assertThat(service.comprasPorNcm(produtorA.getId(), "3105", JAN, JUN).valorTotal())
        .isEqualByComparingTo("3700.00");
  }

  @Test
  @DisplayName("Curingas do LIKE digitados pelo usuario sao texto: '%' nao casa com tudo")
  void curingasSaoLiterais() {
    // "%" literal casa so com a descricao que TEM um "%" ("Glifosato 100% puro"); como curinga
    // casaria com todas as compras do semestre (R$ 4.150,00).
    assertThat(service.comprasPorDescricao(produtorA.getId(), "%", JAN, JUN).valorTotal())
        .isEqualByComparingTo("450.00");
    assertThat(service.comprasPorDescricao(produtorA.getId(), "_", JAN, JUN).quantidadeNotas())
        .isZero();
    assertThat(service.comprasPorDescricao(produtorA.getId(), "100%", JAN, JUN).valorTotal())
        .isEqualByComparingTo("450.00");
  }

  @Test
  @DisplayName("Compras por prefixo de NCM (3808 = defensivos)")
  void comprasPorPrefixoNcm() {
    ComprasInsumoResultado r = service.comprasPorNcm(produtorA.getId(), "3808", JAN, JUN);

    assertThat(r.valorTotal()).isEqualByComparingTo("450.00");
    assertThat(r.quantidadeNotas()).isEqualTo(1);
  }

  @Test
  @DisplayName("Listar notas: limite respeitado, total informado, so do produtor")
  void listarNotasLimitada() {
    Page<NotaResumo> pagina = service.listarNotas(produtorA.getId(),
        EnumSet.allOf(OrigemNotaFiscal.class), EnumSet.allOf(StatusNotaFiscal.class),
        JAN, LocalDate.of(2026, 12, 31), 2);

    assertThat(pagina.getContent()).hasSize(2);
    assertThat(pagina.getTotalElements()).isEqualTo(6);
    // Mais recente primeiro.
    assertThat(pagina.getContent().get(0).dataEmissao().toLocalDate())
        .isEqualTo(LocalDate.of(2026, 7, 1));
  }

  @Test
  @DisplayName("Listar notas nunca devolve mais que o teto, mesmo pedindo mais")
  void listarNotasRespeitaTeto() {
    assertThat(service.listarNotas(produtorA.getId(), EnumSet.allOf(OrigemNotaFiscal.class),
        EnumSet.allOf(StatusNotaFiscal.class), JAN, LocalDate.of(2026, 12, 31), 1000)
        .getSize()).isEqualTo(ConsultaDadosProdutorService.LIMITE_MAXIMO);
  }

  @Test
  @DisplayName("Vencimentos: so guias nao pagas do produtor na janela, da mais proxima")
  void vencimentosNaoPagos() {
    LocalDate hoje = LocalDate.of(2026, 9, 29);
    guiaFiscalRepository.save(guia(produtorA, hoje.plusDays(10), "300.00",
        StatusGuiaFiscal.PENDENTE));
    guiaFiscalRepository.save(guia(produtorA, hoje.plusDays(2), "100.00",
        StatusGuiaFiscal.PENDENTE));
    guiaFiscalRepository.save(guia(produtorA, hoje.plusDays(5), "999.00", StatusGuiaFiscal.PAGA));
    guiaFiscalRepository.save(guia(produtorA, hoje.plusDays(60), "50.00",
        StatusGuiaFiscal.PENDENTE));
    guiaFiscalRepository.save(guia(produtorB, hoje.plusDays(3), "777.00",
        StatusGuiaFiscal.PENDENTE));

    Page<VencimentoGuiaResumo> pagina =
        service.vencimentosNaoPagos(produtorA.getId(), hoje, hoje.plusDays(30), 10);

    assertThat(pagina.getContent()).extracting(VencimentoGuiaResumo::dataVencimento)
        .containsExactly(hoje.plusDays(2), hoje.plusDays(10));
    assertThat(pagina.getTotalElements()).isEqualTo(2);
    // #169: soma no mesmo recorte (sem a paga, sem a de fora da janela, sem a do B).
    TotalAgregado total =
        service.totalizarVencimentosNaoPagos(produtorA.getId(), hoje, hoje.plusDays(30));
    assertThat(total.valor()).isEqualByComparingTo("400.00");
    assertThat(total.quantidade()).isEqualTo(2);
  }

  @Test
  @DisplayName("#169: soma dos vencimentos cobre a janela inteira, nao so a pagina listada")
  void somaDosVencimentosAlemDaPagina() {
    LocalDate hoje = LocalDate.of(2026, 9, 29);
    for (int dia = 1; dia <= 12; dia++) {
      guiaFiscalRepository.save(guia(produtorA, hoje.plusDays(dia), "10.05",
          StatusGuiaFiscal.PENDENTE));
    }

    assertThat(service.vencimentosNaoPagos(produtorA.getId(), hoje, hoje.plusDays(30), 10)
        .getContent()).hasSize(10);
    TotalAgregado total =
        service.totalizarVencimentosNaoPagos(produtorA.getId(), hoje, hoje.plusDays(30));
    assertThat(total.valor()).isEqualByComparingTo("120.60");
    assertThat(total.quantidade()).isEqualTo(12);
    TotalAgregado doB =
        service.totalizarVencimentosNaoPagos(produtorB.getId(), hoje, hoje.plusDays(30));
    assertThat(doB.valor()).isEqualByComparingTo("0.00").hasScaleOf(2);
    assertThat(doB.quantidade()).isZero();
  }

  @Test
  @DisplayName("#169: soma das notas no mesmo filtro da listagem, isolada por produtor")
  void somaDasNotasNoFiltro() {
    LocalDate dez = LocalDate.of(2026, 12, 31);

    TotalAgregado autorizadas = service.totalizarNotas(produtorA.getId(),
        EnumSet.allOf(OrigemNotaFiscal.class), EnumSet.of(StatusNotaFiscal.AUTORIZADA), JAN, dez);
    assertThat(autorizadas.valor()).isEqualByComparingTo("5250.00");
    assertThat(autorizadas.quantidade()).isEqualTo(5);
    TotalAgregado todas = service.totalizarNotas(produtorA.getId(),
        EnumSet.allOf(OrigemNotaFiscal.class), EnumSet.allOf(StatusNotaFiscal.class), JAN, dez);
    assertThat(todas.valor()).isEqualByComparingTo("6150.00");
    assertThat(todas.quantidade()).isEqualTo(6);
    assertThat(service.totalizarNotas(produtorA.getId(), EnumSet.of(OrigemNotaFiscal.IMPORTADA),
        EnumSet.of(StatusNotaFiscal.AUTORIZADA), JAN, JUN).valor())
        .isEqualByComparingTo("4150.00");
    assertThat(service.totalizarNotas(produtorB.getId(), EnumSet.allOf(OrigemNotaFiscal.class),
        EnumSet.allOf(StatusNotaFiscal.class), JAN, dez).valor())
        .isEqualByComparingTo("99999.00");
    TotalAgregado vazio = service.totalizarNotas(produtorA.getId(),
        EnumSet.allOf(OrigemNotaFiscal.class), EnumSet.allOf(StatusNotaFiscal.class),
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31));
    assertThat(vazio.valor()).isEqualByComparingTo("0.00");
    assertThat(vazio.quantidade()).isZero();
  }
}
