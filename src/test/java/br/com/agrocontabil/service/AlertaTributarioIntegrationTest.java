package br.com.agrocontabil.service;

import static br.com.agrocontabil.support.DadosCeresFixture.em;
import static br.com.agrocontabil.support.DadosCeresFixture.entrada;
import static br.com.agrocontabil.support.DadosCeresFixture.guia;
import static br.com.agrocontabil.support.DadosCeresFixture.saida;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.dto.AlertaTributarioResponse;
import br.com.agrocontabil.mapper.AlertaTributarioMapper;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.AlertaTributarioRepository;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Alertas tributários contra o banco (issue #140, RN010): janela de "menos de 5 dias", RN006,
 * fechamento do mês, idempotência do job, isolamento entre produtores e motivo conferido na
 * leitura. Relógio fixo em 27/10/2026, 9h de Brasília (o mês fecha no dia 31, dentro da janela).
 */
@SpringBootTest
@Transactional
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AlertaTributarioIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final LocalDate HOJE = LocalDate.of(2026, 10, 27);

  @Autowired private AlertaTributarioRepository alertaRepository;
  @Autowired private GuiaFiscalRepository guiaRepository;
  @Autowired private NotaFiscalRepository notaRepository;
  @Autowired private ApuracaoMensalRepository apuracaoRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private AuthenticatedProdutorProvider produtorProvider;
  @Autowired private TestDatabaseCleaner cleaner;

  private AlertaTributarioService service;
  private Produtor produtorA;
  private Produtor produtorB;
  private GuiaFiscal guiaQueVence;
  private NotaFiscal notaNoPrazoFinal;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    Clock clock = Clock.fixed(ZonedDateTime.of(HOJE.atTime(9, 0), ZONE).toInstant(), ZONE);
    service = new AlertaTributarioService(alertaRepository, guiaRepository, notaRepository,
        produtorRepository, produtorProvider, new AlertaTributarioMapper(),
        clock);

    produtorA = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda A").cpfCnpj("98765432100").estado("MT").build());
    produtorB = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda B").cpfCnpj("11122233344").estado("PR").build());

    // Guias: so a pendente com vencimento de hoje a hoje + 4 alerta.
    guiaQueVence = guiaRepository.save(guia(produtorA, HOJE.plusDays(2), "1840.55",
        StatusGuiaFiscal.PENDENTE));
    guiaRepository.save(guia(produtorA, HOJE, "10.00", StatusGuiaFiscal.PENDENTE));
    guiaRepository.save(guia(produtorA, HOJE.plusDays(5), "20.00", StatusGuiaFiscal.PENDENTE));
    guiaRepository.save(guia(produtorA, HOJE.plusDays(1), "30.00", StatusGuiaFiscal.PAGA));
    guiaRepository.save(guia(produtorA, HOJE.minusDays(1), "40.00", StatusGuiaFiscal.PENDENTE));
    guiaRepository.save(guia(produtorB, HOJE.plusDays(3), "777.00", StatusGuiaFiscal.PENDENTE));

    // RN006: emissao + 30 dias. 28/09 -> prazo 28/10 (alerta); 26/09 -> 26/10 (passou);
    // 02/10 -> 01/11 (fora da janela). Cancelada e importada nunca alertam.
    notaNoPrazoFinal = notaRepository.save(saida(produtorA, em(2026, 9, 28), "Soja", "12019000",
        "5000.00"));
    notaRepository.save(saida(produtorA, em(2026, 9, 26), "Soja", "12019000", "5000.00"));
    notaRepository.save(saida(produtorA, em(2026, 10, 2), "Soja", "12019000", "5000.00"));
    NotaFiscal cancelada = saida(produtorA, em(2026, 9, 28), "Soja", "12019000", "5000.00");
    cancelada.setStatus(StatusNotaFiscal.CANCELADA);
    notaRepository.save(cancelada);
    notaRepository.save(entrada(produtorA, StatusNotaFiscal.AUTORIZADA, em(2026, 9, 28),
        "Adubo", "31052000", "KG", "10", "100.00"));

    // Apuracao: aberta do mes alerta; fechada nao.
    apuracaoRepository.save(apuracao(produtorA, "2026-10", StatusApuracao.ABERTO));
    apuracaoRepository.save(apuracao(produtorB, "2026-10", StatusApuracao.FECHADO));
  }

  @Test
  @DisplayName("DoD #140: alerta gerado de hoje ate hoje + 4, do mais urgente ao mais distante")
  void geraAlertasNaJanela() {
    AlertaTributarioService.ResumoAlertas resumo = service.gerarAlertas();

    List<AlertaTributarioResponse> alertas = service.listarAtivos(produtorA.getId());
    assertThat(alertas)
        .extracting(AlertaTributarioResponse::tipo, AlertaTributarioResponse::dataLimite,
            AlertaTributarioResponse::diasRestantes)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("VENCIMENTO_GUIA", HOJE, 0L),
            org.assertj.core.groups.Tuple.tuple("PRAZO_CANCELAMENTO_NFE", HOJE.plusDays(1), 1L),
            org.assertj.core.groups.Tuple.tuple("VENCIMENTO_GUIA", HOJE.plusDays(2), 2L),
            org.assertj.core.groups.Tuple.tuple("FECHAMENTO_APURACAO", HOJE.plusDays(4), 4L));
    assertThat(alertas.get(2).descricao()).isEqualTo("Guia de FUNRURAL de R$ 1.840,55");
    assertThat(alertas.get(2).referenciaId()).isEqualTo(guiaQueVence.getId());
    assertThat(alertas.get(1).descricao())
        .isEqualTo("Prazo para cancelar a NF-e " + notaNoPrazoFinal.getNumero() + ", série 1");
    assertThat(alertas.get(3).descricao()).isEqualTo("Fechamento da apuração de 10/2026");
    assertThat(resumo.criados()).isEqualTo(5); // 4 do A + 1 do B
  }

  @Test
  @DisplayName("Isolamento: cada produtor ve so os proprios alertas")
  void isolamentoEntreProdutores() {
    service.gerarAlertas();

    assertThat(service.listarAtivos(produtorB.getId()))
        .singleElement()
        .satisfies(a -> assertThat(a.descricao()).isEqualTo("Guia de FUNRURAL de R$ 777,00"));
  }

  @Test
  @DisplayName("Job idempotente: rodar de novo no mesmo dia nao duplica")
  void idempotente() {
    service.gerarAlertas();

    AlertaTributarioService.ResumoAlertas segunda = service.gerarAlertas();

    assertThat(segunda.criados()).isZero();
    assertThat(alertaRepository.count()).isEqualTo(5);
  }

  @Test
  @DisplayName("Guia paga some na hora (leitura confere o motivo); o job seguinte resolve")
  void guiaPagaSomeNaHora() {
    service.gerarAlertas();
    guiaQueVence.setStatus(StatusGuiaFiscal.PAGA);
    guiaRepository.saveAndFlush(guiaQueVence);

    assertThat(service.listarAtivos(produtorA.getId()))
        .extracting(AlertaTributarioResponse::referenciaId)
        .doesNotContain(guiaQueVence.getId());
    assertThat(service.gerarAlertas().resolvidos()).isEqualTo(1);
  }

  @Test
  @DisplayName("Nota cancelada e apuracao fechada tambem deixam de alertar")
  void notaCanceladaEApuracaoFechada() {
    service.gerarAlertas();
    notaNoPrazoFinal.setStatus(StatusNotaFiscal.CANCELADA);
    notaRepository.saveAndFlush(notaNoPrazoFinal);
    ApuracaoMensal apuracao =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtorA.getId(), "2026-10").orElseThrow();
    apuracao.setStatus(StatusApuracao.FECHADO);
    apuracaoRepository.saveAndFlush(apuracao);

    assertThat(service.listarAtivos(produtorA.getId()))
        .extracting(AlertaTributarioResponse::tipo)
        .containsOnly("VENCIMENTO_GUIA");
  }

  @Test
  @DisplayName("Referencia de outro produtor nao alerta, mesmo gravada (defesa em profundidade)")
  void referenciaDeOutroProdutorNaoAparece() {
    service.gerarAlertas();
    GuiaFiscal guiaDoB = guiaRepository.findAll().stream()
        .filter(g -> g.getValor().compareTo(new BigDecimal("777.00")) == 0)
        .findFirst().orElseThrow();
    // Um alerta do A apontando para a guia do B (bug ou dado corrompido): nao vaza para o A.
    // Data diferente da do alerta legitimo do B (HOJE + 3), que o job acabou de gravar: a tripla
    // (tipo, referencia, data) e unica no banco, e repeti-la so passava quando o H2 do contexto
    // estava sem o indice — o que dependia da ordem em que os contextos de teste sobem.
    alertaRepository.saveAndFlush(br.com.agrocontabil.model.AlertaTributario.builder()
        .produtor(produtorA)
        .tipo(br.com.agrocontabil.model.TipoAlertaTributario.VENCIMENTO_GUIA)
        .referenciaId(guiaDoB.getId())
        .dataLimite(HOJE.plusDays(2))
        .descricao("Guia de FUNRURAL de R$ 777,00")
        .status(br.com.agrocontabil.model.StatusAlertaTributario.ATIVO)
        .criadoEm(java.time.OffsetDateTime.now())
        .build());

    assertThat(service.listarAtivos(produtorA.getId()))
        .extracting(AlertaTributarioResponse::referenciaId)
        .doesNotContain(guiaDoB.getId());
  }

  @Test
  @DisplayName("Review: fechamento alerta sem linha de apuracao (o mes corrente e projecao ao vivo)")
  void fechamentoSemLinhaDeApuracao() {
    Produtor produtorC = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Fazenda C").cpfCnpj("22233344455").estado("GO").build());
    // Nota de outubro e nenhuma apuracao gravada: o caso normal antes do fechamento (#53).
    notaRepository.save(saida(produtorC, em(2026, 10, 10), "Milho", "10059010", "800.00"));

    service.gerarAlertas();

    assertThat(service.listarAtivos(produtorC.getId()))
        .singleElement()
        .satisfies(a -> {
          assertThat(a.tipo()).isEqualTo("FECHAMENTO_APURACAO");
          assertThat(a.dataLimite()).isEqualTo(LocalDate.of(2026, 10, 31));
          assertThat(a.referenciaId()).isEqualTo(produtorC.getId());
        });
  }

  @Test
  @DisplayName("Fora da janela do fechamento (dia 20), a apuracao aberta nao alerta")
  void apuracaoSoNaSemanaDoFechamento() {
    Clock dia20 = Clock.fixed(
        ZonedDateTime.of(LocalDate.of(2026, 10, 20).atTime(9, 0), ZONE).toInstant(), ZONE);
    AlertaTributarioService noDia20 = new AlertaTributarioService(alertaRepository,
        guiaRepository, notaRepository, produtorRepository, produtorProvider,
        new AlertaTributarioMapper(), dia20);

    noDia20.gerarAlertas();

    assertThat(noDia20.listarAtivos(produtorA.getId()))
        .extracting(AlertaTributarioResponse::tipo)
        .doesNotContain("FECHAMENTO_APURACAO");
  }

  private static ApuracaoMensal apuracao(Produtor produtor, String periodo, StatusApuracao status) {
    return ApuracaoMensal.builder()
        .produtor(produtor)
        .periodo(periodo)
        .status(status)
        .debitoIbs(BigDecimal.ZERO)
        .debitoCbs(BigDecimal.ZERO)
        .creditoIbs(BigDecimal.ZERO)
        .creditoCbs(BigDecimal.ZERO)
        .saldoIbs(BigDecimal.ZERO)
        .saldoCbs(BigDecimal.ZERO)
        .totalNotas(1)
        .build();
  }
}
