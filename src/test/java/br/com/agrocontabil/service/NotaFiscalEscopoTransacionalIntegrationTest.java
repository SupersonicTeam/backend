package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escopo transacional da emissão e da DANFE (Onda 3, issues #99 e #97).
 *
 * <p><strong>Os dois defeitos que esta classe trava:</strong>
 *
 * <p><em>#99</em> — {@code NotaFiscalService} era {@code @Transactional} na classe, então a
 * assinatura RSA, a validação XSD e a renderização inteira do JasperReports rodavam com a transação
 * aberta. Cada emissão ou download mantinha uma conexão do pool ocupada durante todo o
 * processamento; N requisições concorrentes esgotavam o pool sem que nenhuma consulta estivesse em
 * curso.
 *
 * <p><em>#97</em> — o registro de inconsistências era {@code REQUIRES_NEW} e era chamado de dentro
 * dessa transação: cada requisição de nota inválida precisava de <strong>duas</strong> conexões
 * simultâneas, uma delas presa esperando a outra. Com o pool cheio de requisições nesse estado,
 * todas travavam à espera de conexões que ninguém soltava.
 *
 * <p><strong>Pool de tamanho 1 é o instrumento de medida.</strong> Com o defeito, o fluxo de nota
 * inválida não tem como completar: precisaria de 2 conexões e só existe 1. Como
 * {@code connection-timeout} é curto, o defeito aparece como <em>falha rápida</em> do teste, não
 * como suíte pendurada.
 *
 * <p>A classe <strong>não</strong> usa {@code @Transactional}: com uma transação de teste
 * envolvendo tudo, o serviço herdaria transação e o cenário — justamente a ausência dela —
 * desapareceria.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // 1 conexão: o fluxo de nota inválida precisava de 2 antes da correção.
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.datasource.hikari.minimum-idle=1",
    // Timeout curto para que a exaustão do pool FALHE o teste em vez de pendurar a suíte.
    "spring.datasource.hikari.connection-timeout=3000",
})
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NotaFiscalEscopoTransacionalIntegrationTest {

  private static final int REQUISICOES_CONCORRENTES = 10;
  private static final int TIMEOUT_SEGUNDOS = 60;

  @Autowired private NotaFiscalService notaFiscalService;
  @Autowired private NotaFiscalRepository notaFiscalRepository;
  @Autowired private NotaFiscalValidacaoRepository validacaoRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;
  @Autowired private TransactionTemplate transactionTemplate;

  /** Espiao usado apenas para editar a nota no meio da fase 2 (janela da issue #99). */
  @SpyBean private GeracaoNotaFiscalService geracaoNotaFiscalService;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    transactionTemplate.executeWithoutResult(status -> {
      cleaner.limparTudo();
      Produtor completo = ProdutorTestFixtures.produtorCompleto();
      completo.setId(null);
      produtor = produtorRepository.save(completo);
    });
    autenticar();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    transactionTemplate.executeWithoutResult(status -> cleaner.limparTudo());
  }

  @Test
  @DisplayName("nota invalida com pool de 1 conexao: 422 e historico persistido, sem travar")
  void notaInvalidaComPoolUnitarioNaoTrava() {
    UUID notaId = persistir(buildNotaInvalida());

    // Antes da correção este fluxo pedia 2 conexões (a externa presa + a REQUIRES_NEW) e, com
    // pool de 1, não tinha como completar.
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);

    // A garantia da issue #22 continua valendo: o histórico sobrevive ao 422 devolvido ao cliente.
    List<NotaFiscalValidacao> registros =
        validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId);
    assertThat(registros).isNotEmpty();
    assertThat(notaFiscalRepository.findById(notaId).orElseThrow().getStatus())
        .isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
  }

  @Test
  @DisplayName("10 notas invalidas concorrentes com pool de 1: todas completam, nenhuma trava")
  void dezNotasInvalidasConcorrentesComPoolUnitarioNaoTravam() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < REQUISICOES_CONCORRENTES; i++) {
      ids.add(persistir(buildNotaInvalida()));
    }

    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(REQUISICOES_CONCORRENTES);
    try {
      List<Future<Throwable>> futures = new ArrayList<>();
      for (UUID id : ids) {
        Callable<Throwable> tarefa = () -> {
          autenticar();
          largada.await(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
          try {
            notaFiscalService.gerarXml(id);
            return null;
          } catch (Throwable e) {
            return e;
          } finally {
            SecurityContextHolder.clearContext();
          }
        };
        futures.add(executor.submit(tarefa));
      }
      largada.countDown();

      // Toda requisição tem de terminar com o 422 de negócio. Exaustão de pool apareceria aqui
      // como CannotCreateTransactionException/SQLTransientConnectionException — e é isso que o
      // defeito das issues #97/#99 produzia.
      for (Future<Throwable> future : futures) {
        Throwable resultado = future.get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
        assertThat(resultado).isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)).isTrue();
    }

    // Todas as notas ficaram com histórico e status corretos — nenhuma requisição morreu no meio.
    for (UUID id : ids) {
      assertThat(validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(id)).isNotEmpty();
      assertThat(notaFiscalRepository.findById(id).orElseThrow().getStatus())
          .isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
    }
  }

  @Test
  @DisplayName("gerar XML de nota completa fora de transacao nao toca relacao LAZY")
  void gerarXmlDeNotaCompletaNaoAcessaRelacaoLazy() {
    // Três itens e produtor com endereço/IE: o pipeline percorre itens e emitente inteiros. Com
    // open-in-view=false e a geração fora de transação, um campo LAZY que ficasse de fora do
    // JOIN FETCH estouraria LazyInitializationException aqui.
    UUID notaId = persistir(buildNotaValida(3));

    assertThatCode(() -> notaFiscalService.gerarXml(notaId)).doesNotThrowAnyException();

    NotaFiscal emitida = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(emitida.getStatus()).isEqualTo(StatusNotaFiscal.XML_GERADO);
    assertThat(emitida.getChaveAcesso()).isNotNull().hasSize(44);
    assertThat(emitida.getXmlGerado()).contains("Id=\"NFe" + emitida.getChaveAcesso() + "\"");
    // Os três itens chegaram ao mapper: o grafo veio completo, não truncado pela fronteira da
    // transação. Se o JOIN FETCH dos itens saísse, aqui viria LazyInitializationException — não
    // um XML com menos itens.
    assertThat(emitida.getXmlGerado())
        .contains("nItem=\"1\"")
        .contains("nItem=\"2\"")
        .contains("nItem=\"3\"");
  }

  @Test
  @DisplayName("gerar DANFE de nota completa fora de transacao nao toca relacao LAZY")
  void gerarPdfDeNotaCompletaNaoAcessaRelacaoLazy() {
    UUID notaId = persistir(buildNotaValida(3));
    notaFiscalService.gerarXml(notaId);

    // O DanfePdfService lê itens, produtor e endereço do destinatário para montar o relatório —
    // tudo fora de transação desde a issue #99.
    byte[] pdf = notaFiscalService.gerarPdf(notaId);

    assertThat(pdf).isNotEmpty();
    // Assinatura de arquivo PDF: garante que o Jasper rodou até o fim, não que só não lançou.
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  @DisplayName("edicao concorrente durante a geracao: o XML divergente NAO e persistido")
  void edicaoConcorrenteDuranteGeracaoNaoPersisteXmlDivergente() {
    UUID notaId = persistir(buildNotaValida(3));

    // Reproduz a janela que a fase 2 abre: a edicao acontece DURANTE a assinatura, nao antes dela.
    // Interceptar a geracao e a unica forma deterministica de cair nesse intervalo — com a edicao
    // feita antes da chamada, as fases 1 e 3 leem a MESMA versao e nada e detectado (foi assim que
    // a primeira versao deste teste passou sem provar nada).
    doAnswer(invocacao -> {
      transactionTemplate.executeWithoutResult(status -> {
        NotaFiscal emEdicao = notaFiscalRepository.findById(notaId).orElseThrow();
        emEdicao.setNumero(emEdicao.getNumero() + 1);
        notaFiscalRepository.saveAndFlush(emEdicao);
      });
      return invocacao.callRealMethod();
    }).when(geracaoNotaFiscalService).gerarXml(org.mockito.ArgumentMatchers.any(NotaFiscal.class));

    // update e permitido em RASCUNHO, entao a recheca de STATUS da fase 3 passaria: o XML assinado
    // sobre o estado antigo seria gravado sobre a linha ja alterada, com uma chave de acesso cujo
    // nNF nao corresponde ao numero atual — e a nota ficaria imutavel assim, pronta para ir a
    // SEFAZ. A comparacao de VERSAO entre as fases e o que recusa a gravacao.
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(OptimisticLockingFailureException.class);

    // A nota permanece intacta e emitivel: nada de XML, chave ou XML_GERADO pela metade.
    NotaFiscal depois = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(depois.getStatus()).isEqualTo(StatusNotaFiscal.RASCUNHO);
    assertThat(depois.getXmlGerado()).isNull();
    assertThat(depois.getChaveAcesso()).isNull();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void autenticar() {
    SecurityContextHolder.getContext().setAuthentication(
        AutenticacaoTestSupport.tokenDe(produtor));
  }

  private UUID persistir(NotaFiscal nota) {
    return transactionTemplate.execute(status -> notaFiscalRepository.save(nota)).getId();
  }

  /** Nota sem itens e sem endereço do destinatário — reprovada pelo {@code FiscalRulesValidator}. */
  private NotaFiscal buildNotaInvalida() {
    return NotaFiscal.builder()
        .numero(proximoNumero())
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Comprador Sem Endereco")
        .destinatarioCpfCnpj("98765432100")
        .valorTotalProdutos(BigDecimal.ZERO)
        .valorTotalNota(BigDecimal.ZERO)
        .status(StatusNotaFiscal.RASCUNHO)
        .build();
  }

  /** Nota completa o bastante para emitir, com a quantidade de itens informada. */
  private NotaFiscal buildNotaValida(int quantidadeItens) {
    NotaFiscal nota = NotaFiscal.builder()
        .numero(proximoNumero())
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Comprador Teste")
        .destinatarioCpfCnpj("98765432100")
        .destinatarioEndereco(EnderecoDestinatario.builder()
            .logradouro("Rua das Acacias")
            .numero("250")
            .municipio("Uberaba")
            .codigoMunicipioIbge("3170206")
            .uf("MG")
            .cep("38100000")
            .build())
        .status(StatusNotaFiscal.RASCUNHO)
        .build();

    BigDecimal total = BigDecimal.ZERO;
    for (int i = 1; i <= quantidadeItens; i++) {
      BigDecimal valor = new BigDecimal("1000.00");
      nota.addItem(NotaFiscalItem.builder()
          .numeroItem(i)
          .descricao("Soja em graos - lote " + i)
          .ncm("12019000")
          .cfop("6101")
          .unidade("KG")
          .quantidade(BigDecimal.ONE)
          .valorUnitario(valor)
          .valorTotal(valor)
          .build());
      total = total.add(valor);
    }
    nota.setValorTotalProdutos(total);
    nota.setValorTotalNota(total);
    return nota;
  }

  /**
   * Número de nota distinto por chamada: {@code (produtor, numero, serie, modelo)} é único para
   * notas próprias (índice parcial do changeSet 0009).
   */
  private long proximoNumero() {
    return System.nanoTime() % 900_000_000L + 1;
  }
}
