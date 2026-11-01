package br.com.agrocontabil.service;

import static br.com.agrocontabil.support.DadosCeresFixture.em;
import static br.com.agrocontabil.support.DadosCeresFixture.entrada;
import static br.com.agrocontabil.support.DadosCeresFixture.guia;
import static br.com.agrocontabil.support.DadosCeresFixture.saida;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalRevisao;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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

  @Test
  @DisplayName("#170: NCMs comprados no mesmo recorte das compras, maior valor primeiro")
  void ncmsComprados() {
    // Cancelada, fora do periodo, saida e do produtor B nao entram.
    assertThat(service.ncmsComprados(produtorA.getId(), "fertilizante", JAN, JUN, 3))
        .containsExactly("31052000", "31059090");
    assertThat(service.ncmsComprados(produtorA.getId(), "3105", JAN, JUN, 3))
        .containsExactly("31052000", "31059090");
    assertThat(service.ncmsComprados(produtorA.getId(), "fertilizante", JAN, JUN, 1))
        .containsExactly("31052000");
    assertThat(service.ncmsComprados(produtorB.getId(), "glifosato", JAN, JUN, 3)).isEmpty();
    // Curinga digitado e texto, como nas compras.
    assertThat(service.ncmsComprados(produtorA.getId(), "%", JAN, JUN, 3))
        .containsExactly("38089324");
  }

  @Autowired private NotaFiscalRevisaoRepository revisaoRepository;
  @Autowired private NotaFiscalValidacaoRepository validacaoRepository;

  @Test
  @DisplayName("#256: nota pelo numero so do produtor, propria primeiro, com o motivo atual")
  void notaPorNumero() {
    NotaFiscal minha = saida(produtorA, em(2026, 9, 8), "Milho", "10059010", "1000.00");
    minha.setNumero(777_001L);
    minha.setStatus(StatusNotaFiscal.DEVOLVIDA);
    minha = notaFiscalRepository.save(minha);
    // Compra de fornecedor com o mesmo numero, mais recente: vem depois da propria.
    NotaFiscal recebida = entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 9, 20),
        "Adubo", "31052000", "KG", "10", "50.00");
    recebida.setNumero(777_001L);
    notaFiscalRepository.save(recebida);
    NotaFiscal outra = saida(produtorB, em(2026, 9, 8), "Milho", "10059010", "9999.00");
    outra.setNumero(777_001L);
    notaFiscalRepository.save(outra);
    revisaoRepository.save(NotaFiscalRevisao.builder()
        .notaFiscalId(minha.getId()).acao(AcaoRevisao.DEVOLVIDA)
        .motivo("motivo antigo").ocorridoEm(em(2026, 9, 9)).build());
    revisaoRepository.save(NotaFiscalRevisao.builder()
        .notaFiscalId(minha.getId()).acao(AcaoRevisao.DEVOLVIDA)
        .motivo("motivo novo").ocorridoEm(em(2026, 9, 11)).build());
    // Lote antigo de uma nota devolvida: pode ser de erros ja corrigidos, nao aparece.
    validacaoRepository.save(validacao(minha, UUID.randomUUID(), "VELHO", em(2026, 9, 7)));

    ConsultaDadosProdutorService.NotasPorNumero r =
        service.notasPorNumero(produtorA.getId(), 777_001L);

    // A do B, com o mesmo numero, nao aparece nem conta (anti-IDOR).
    assertThat(r.total()).isEqualTo(2);
    assertThat(r.notas()).extracting(NotaPorNumeroResultado::origem)
        .containsExactly(OrigemNotaFiscal.PROPRIA, OrigemNotaFiscal.IMPORTADA);
    NotaPorNumeroResultado propria = r.notas().get(0);
    assertThat(propria.valorTotal()).isEqualByComparingTo("1000.00");
    assertThat(propria.motivoDevolucao()).isEqualTo("motivo novo");
    assertThat(propria.inconsistencias()).isEmpty();
    // A compra autorizada nunca foi devolvida.
    assertThat(r.notas().get(1).motivoDevolucao()).isNull();
    assertThat(service.notasPorNumero(produtorA.getId(), 123_456_789L).notas()).isEmpty();
  }

  @Test
  @DisplayName("#256: com inconsistencias, so o ultimo lote")
  void notaComInconsistencias() {
    NotaFiscal nota = saida(produtorA, em(2026, 9, 8), "Milho", "10059010", "1000.00");
    nota.setNumero(777_002L);
    nota.setStatus(StatusNotaFiscal.COM_INCONSISTENCIAS);
    nota = notaFiscalRepository.save(nota);
    validacaoRepository.save(validacao(nota, UUID.randomUUID(), "VELHO", em(2026, 9, 9)));
    validacaoRepository.save(validacao(nota, UUID.randomUUID(), "NOVO", em(2026, 9, 10)));

    assertThat(service.notasPorNumero(produtorA.getId(), 777_002L).notas()).singleElement()
        .satisfies(n -> assertThat(n.inconsistencias())
            .extracting(NotaPorNumeroResultado.Inconsistencia::codigo).containsExactly("NOVO"));
  }

  private static NotaFiscalValidacao validacao(NotaFiscal nota,
      UUID lote, String codigo, OffsetDateTime quando) {
    return NotaFiscalValidacao.builder()
        .notaFiscal(nota).loteValidacao(lote).code(codigo).message("m").validadoEm(quando)
        .build();
  }

  @Test
  @DisplayName("#248: vendas de um produto: so saidas autorizadas do produtor, por nome ou NCM")
  void vendasDeProduto() {
    notaFiscalRepository.save(saida(produtorA, em(2026, 8, 5), "Soja em graos", "12019000",
        "75600.00"));
    notaFiscalRepository.save(saida(produtorA, em(2026, 8, 6), "Milho em graos", "10059010",
        "1000.00"));
    NotaFiscal cancelada = saida(produtorA, em(2026, 8, 7), "Soja em graos", "12019000",
        "500.00");
    cancelada.setStatus(StatusNotaFiscal.CANCELADA);
    notaFiscalRepository.save(cancelada);
    notaFiscalRepository.save(saida(produtorB, em(2026, 8, 5), "Soja em graos", "12019000",
        "1234567.89"));
    // Remessa para armazem (CFOP 5949) nao e venda; rejeitada pela SEFAZ tambem nao.
    NotaFiscal remessa = saida(produtorA, em(2026, 8, 8), "Soja em graos", "12019000",
        "30000.00");
    remessa.getItens().get(0).setCfop("5949");
    notaFiscalRepository.save(remessa);
    NotaFiscal rejeitada = saida(produtorA, em(2026, 8, 9), "Soja em graos", "12019000",
        "700.00");
    rejeitada.setStatus(StatusNotaFiscal.REJEITADA);
    notaFiscalRepository.save(rejeitada);
    // Compra de soja do proprio produtor: e entrada, nao venda.
    notaFiscalRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 8, 5),
        "Semente de soja", "12011000", "KG", "10", "800.00"));

    ComprasInsumoResultado porNome = service.vendasDeProduto(produtorA.getId(), "SOJA", false,
        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
    ComprasInsumoResultado porNcm = service.vendasDeProduto(produtorA.getId(), "1201", true,
        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

    assertThat(porNome.valorTotal()).isEqualByComparingTo("75600.00");
    assertThat(porNome.quantidadeNotas()).isEqualTo(1);
    assertThat(porNcm.valorTotal()).isEqualByComparingTo("75600.00");
    assertThat(porNcm.quantidadeNotas()).isEqualTo(1);
    // Ultimo dia inclusivo, no fuso de Brasilia: a venda de 31/08 as 23h entra.
    notaFiscalRepository.save(saida(produtorA,
        java.time.OffsetDateTime.of(2026, 8, 31, 23, 0, 0, 0, java.time.ZoneOffset.ofHours(-3)),
        "Soja em graos", "12019000", "100.00"));
    assertThat(service.vendasDeProduto(produtorA.getId(), "soja", false,
        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)).valorTotal())
        .isEqualByComparingTo("75700.00");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.vendasDeProduto(
            produtorA.getId(), "soja", true, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.vendasDeProduto(
            produtorA.getId(), null, false, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
