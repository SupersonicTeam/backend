package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import br.com.agrocontabil.exception.ApuracaoPeriodoFechadoException;
import br.com.agrocontabil.model.ApuracaoMensal;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusApuracao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ApuracaoMensalRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Teste de integração do fechamento da apuração pelo job (issues #41, #57 e #100).
 *
 * <p><strong>Deliberadamente sem {@code @Transactional} de classe:</strong> desde a issue #100 cada
 * produtor é fechado numa transação {@code REQUIRES_NEW} própria. Dentro de uma transação de teste,
 * essas transações não enxergariam os dados ainda não commitados do teste, e o rollback automático
 * esconderia justamente o que interessa provar: que o fechamento dos produtores sem problema foi
 * <em>commitado</em> mesmo com a falha de outro. Os dados são limpos no início de cada teste pelo
 * {@link TestDatabaseCleaner}.
 *
 * <p>O lote de leitura é 2 ({@code app.apuracao.fechamento.lote-tamanho}) para que três produtores
 * atravessem duas páginas do keyset contra o H2 de verdade.
 *
 * <p>A falha de um produtor é injetada por um spy no {@link NotaFiscalRepository}: a agregação de
 * débito lança exceção só para aquele ID, como faria um dado inconsistente.
 */
@SpringBootTest(properties = "app.apuracao.fechamento.lote-tamanho=2")
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class ApuracaoFechamentoIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  private static final String JULHO = "2026-07";
  private static final OffsetDateTime EMISSAO_JULHO =
      OffsetDateTime.of(2026, 7, 20, 8, 0, 0, 0, ZoneOffset.ofHours(-3));

  @Autowired private ApuracaoService apuracaoService;
  @Autowired private ApuracaoMensalRepository apuracaoRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @MockitoSpyBean private NotaFiscalRepository notaFiscalRepository;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();
    produtor = novoProdutor("Maria Agricultora", "98765432100");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Job fecha o periodo do produtor (FECHADO) com data de fechamento")
  void jobFechaPeriodo() {
    salvarSaidaAutorizada(produtor, 1L, EMISSAO_JULHO, "80.00");

    ResumoLoteApuracao resumo = apuracaoService.fecharPeriodo(JULHO);

    assertThat(resumo.concluidas()).isEqualTo(1);
    assertThat(resumo.houveFalha()).isFalse();
    ApuracaoMensal a =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), JULHO).orElseThrow();
    assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
    assertThat(a.getDataFechamento()).isNotNull();
    assertThat(a.getSaldoIbs()).isEqualByComparingTo("80.00");
  }

  @Test
  @DisplayName("Falha de um produtor nao desfaz o fechamento dos demais, que ficam commitados (#100)")
  void falhaDeUmProdutorNaoDesfazOsDemais() {
    Produtor quebrado = novoProdutor("Produtor Inconsistente", "11122233344");
    Produtor terceiro = novoProdutor("Joao Pecuarista", "55566677788");
    salvarSaidaAutorizada(produtor, 1L, EMISSAO_JULHO, "80.00");
    salvarSaidaAutorizada(quebrado, 1L, EMISSAO_JULHO, "999.00");
    salvarSaidaAutorizada(terceiro, 1L, EMISSAO_JULHO, "40.00");
    doThrow(new IllegalStateException("dado inconsistente"))
        .when(notaFiscalRepository).somarDebitosPorPeriodo(eq(quebrado.getId()), any(), any());

    ResumoLoteApuracao resumo = apuracaoService.fecharPeriodo(JULHO);

    // Tres produtores com lote de 2: a varredura atravessou duas paginas do keyset.
    assertThat(resumo.total()).isEqualTo(3);
    assertThat(resumo.concluidas()).isEqualTo(2);
    assertThat(resumo.falhas()).containsExactly(quebrado.getId());
    // Lido depois, fora de qualquer transacao do fechamento: estes commits sobreviveram.
    assertThat(apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), JULHO))
        .get().extracting(ApuracaoMensal::getStatus).isEqualTo(StatusApuracao.FECHADO);
    assertThat(apuracaoRepository.findByProdutorIdAndPeriodo(terceiro.getId(), JULHO))
        .get().extracting(ApuracaoMensal::getStatus).isEqualTo(StatusApuracao.FECHADO);
    // O produtor com falha nao ficou com apuracao parcial gravada.
    assertThat(apuracaoRepository.findByProdutorIdAndPeriodo(quebrado.getId(), JULHO)).isEmpty();
  }

  @Test
  @DisplayName("Fechamento percorre todos os produtores em lotes por keyset, sem pular nem repetir")
  void fechamentoPercorreTodosOsProdutoresEmLotes() {
    novoProdutor("Produtor B", "11122233344");
    novoProdutor("Produtor C", "55566677788");
    novoProdutor("Produtor D", "99988877766");
    novoProdutor("Produtor E", "44433322211");

    ResumoLoteApuracao resumo = apuracaoService.fecharPeriodo(JULHO);

    // 5 produtores, lotes de 2 (2 + 2 + 1): cada um fechado exatamente uma vez.
    assertThat(resumo.concluidas()).isEqualTo(5);
    List<ApuracaoMensal> apuracoes = apuracaoRepository.findAll();
    assertThat(apuracoes).hasSize(5)
        .allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO));
    assertThat(apuracoes).extracting(a -> a.getProdutor().getId()).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("Consolidar apos fechamento lanca 409 e nao rebaixa o periodo (issue #54)")
  void consolidarSobreFechadoRejeitaSemRebaixar() {
    autenticar(produtor);
    salvarSaidaAutorizada(produtor, 1L, EMISSAO_JULHO, "100.00");
    apuracaoService.fecharPeriodo(JULHO);

    assertThatThrownBy(() -> apuracaoService.consolidar(JULHO))
        .isInstanceOf(ApuracaoPeriodoFechadoException.class);

    // Periodo permanece FECHADO — consolidar nao pode rebaixar fora do fluxo de reabertura (RN005).
    ApuracaoMensal a =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), JULHO).orElseThrow();
    assertThat(a.getStatus()).isEqualTo(StatusApuracao.FECHADO);
  }

  @Test
  @DisplayName("Reabre apuracao FECHADA do mes atual: volta a ABERTO com auditoria (quem/quando)")
  void reabreApuracaoFechadaComAuditoria() {
    autenticar(produtor);
    // Periodo do mes corrente -> sempre dentro da janela de 3 meses (RN005), independe da data.
    String periodo = YearMonth.now(ZONE).toString();
    salvarSaidaAutorizada(produtor, 1L, OffsetDateTime.now(ZONE), "80.00");
    apuracaoService.fecharPeriodo(periodo);
    ApuracaoMensal fechada =
        apuracaoRepository.findByProdutorIdAndPeriodo(produtor.getId(), periodo).orElseThrow();
    assertThat(fechada.getStatus()).isEqualTo(StatusApuracao.FECHADO);

    var resposta = apuracaoService.reabrir(fechada.getId());

    assertThat(resposta.status()).isEqualTo("ABERTO");
    ApuracaoMensal reaberta = apuracaoRepository.findById(fechada.getId()).orElseThrow();
    assertThat(reaberta.getStatus()).isEqualTo(StatusApuracao.ABERTO);
    assertThat(reaberta.getDataReabertura()).isNotNull();
    // Auditoria registra o ID do produtor autenticado (identidade mock; nunca CPF/CNPJ — LGPD).
    assertThat(reaberta.getReabertoPor()).isEqualTo(produtor.getId().toString());
    assertThat(reaberta.getReabertoPor()).doesNotContain(produtor.getCpfCnpj());
  }

  @Test
  @DisplayName("Refecha apuracao ABERTA de mes anterior na janela RN005; ignora fora da janela (#57)")
  void refechaReabertaDeMesAnteriorDentroDaJanela() {
    YearMonth mesCorrente = YearMonth.now(ZONE);
    ApuracaoMensal reaberta = abertaEm(produtor, mesCorrente.minusMonths(1).toString());
    ApuracaoMensal antiga = abertaEm(produtor, mesCorrente.minusMonths(4).toString());

    ResumoLoteApuracao resumo = apuracaoService.refecharPeriodosReabertos();

    assertThat(resumo.concluidas()).isEqualTo(1);
    assertThat(resumo.houveFalha()).isFalse();
    ApuracaoMensal refechada = apuracaoRepository.findById(reaberta.getId()).orElseThrow();
    assertThat(refechada.getStatus()).isEqualTo(StatusApuracao.FECHADO);
    assertThat(refechada.getDataFechamento()).isNotNull();
    // Fora da janela de reabertura (RN005): permanece ABERTO — o job nao revisita meses tao antigos.
    assertThat(apuracaoRepository.findById(antiga.getId()).orElseThrow().getStatus())
        .isEqualTo(StatusApuracao.ABERTO);
  }

  @Test
  @DisplayName("Falha ao refechar uma apuracao nao impede o refechamento das demais (issue #100)")
  void falhaAoRefecharUmaNaoImpedeAsDemais() {
    Produtor quebrado = novoProdutor("Produtor Inconsistente", "11122233344");
    String mesAnterior = YearMonth.now(ZONE).minusMonths(1).toString();
    ApuracaoMensal saudavel = abertaEm(produtor, mesAnterior);
    ApuracaoMensal comFalha = abertaEm(quebrado, mesAnterior);
    doThrow(new IllegalStateException("dado inconsistente"))
        .when(notaFiscalRepository).somarDebitosPorPeriodo(eq(quebrado.getId()), any(), any());

    ResumoLoteApuracao resumo = apuracaoService.refecharPeriodosReabertos();

    assertThat(resumo.concluidas()).isEqualTo(1);
    assertThat(resumo.falhas()).containsExactly(comFalha.getId());
    assertThat(apuracaoRepository.findById(saudavel.getId()).orElseThrow().getStatus())
        .isEqualTo(StatusApuracao.FECHADO);
    // A que falhou ficou como estava (rollback so da transacao dela).
    assertThat(apuracaoRepository.findById(comFalha.getId()).orElseThrow().getStatus())
        .isEqualTo(StatusApuracao.ABERTO);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Produtor novoProdutor(String nome, String cpf) {
    return produtorRepository.save(Produtor.builder().nomeCompleto(nome).cpfCnpj(cpf).build());
  }

  private void autenticar(Produtor autenticado) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(autenticado, null, List.of()));
  }

  private ApuracaoMensal abertaEm(Produtor dono, String periodo) {
    return apuracaoRepository.save(ApuracaoMensal.builder()
        .produtor(dono)
        .periodo(periodo)
        .status(StatusApuracao.ABERTO)
        .debitoIbs(BigDecimal.ZERO).debitoCbs(BigDecimal.ZERO)
        .creditoIbs(BigDecimal.ZERO).creditoCbs(BigDecimal.ZERO)
        .saldoIbs(BigDecimal.ZERO).saldoCbs(BigDecimal.ZERO)
        .totalNotas(0L)
        .build());
  }

  private void salvarSaidaAutorizada(
      Produtor emitente, long numero, OffsetDateTime dataEmissao, String ibs) {
    EnderecoDestinatario endereco = EnderecoDestinatario.builder()
        .logradouro("Rua A").numero("1").municipio("Cuiaba")
        .codigoMunicipioIbge("5103403").uf("MT").cep("78000000").build();
    NotaFiscal nota = NotaFiscal.builder()
        .numero(numero).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(dataEmissao)
        .produtor(emitente)
        .destinatarioNome("Jose Comprador").destinatarioCpfCnpj("12345678901")
        .destinatarioEndereco(endereco)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
    nota.setOrigem(OrigemNotaFiscal.PROPRIA);
    nota.setStatus(StatusNotaFiscal.AUTORIZADA);
    nota.setValorTotalIbs(new BigDecimal(ibs));
    nota.setValorTotalCbs(new BigDecimal(ibs));
    notaFiscalRepository.saveAndFlush(nota);
  }
}
