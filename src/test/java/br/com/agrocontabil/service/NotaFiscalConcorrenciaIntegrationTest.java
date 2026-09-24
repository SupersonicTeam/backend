package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.exception.NotaFiscalNaoEditavelException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
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
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

/**
 * Testes de concorrência da geração de XML da NF-e (Onda 3, issue #95).
 *
 * <p><strong>O defeito que estes testes travam:</strong> antes do {@code @Version} em
 * {@link NotaFiscal}, {@code gerarXml} lia a nota RASCUNHO, checava o status, gerava e assinava o
 * XML e salvava. Duas requisições intercaladas passavam as duas pela checagem de status, geravam
 * <em>duas</em> chaves de acesso distintas e o último {@code save} sobrescrevia o primeiro — a nota
 * terminava com uma chave que não corresponde ao XML persistido, defeito fiscal silencioso.
 *
 * <p>A classe <strong>não</strong> usa {@code @Transactional}: cada thread precisa commitar de
 * verdade para que o conflito de versão aconteça. A limpeza é manual no {@link #tearDown()}, no
 * mesmo padrão do {@code NotaFiscalValidacaoFlowTest}.
 *
 * <p>O {@code SecurityContextHolder} é <em>thread-local</em>, então cada thread do
 * {@link ExecutorService} autentica o produtor por conta própria — o que o
 * {@code MockProdutorFilter} faz por requisição em runtime.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(
    scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NotaFiscalConcorrenciaIntegrationTest {

  /** Tempo máximo de espera pelas duas threads — folga suficiente para assinatura RSA + XSD. */
  private static final int TIMEOUT_SEGUNDOS = 30;

  @Autowired
  private NotaFiscalService notaFiscalService;

  @Autowired
  private NotaFiscalRepository notaFiscalRepository;

  @Autowired
  private ProdutorRepository produtorRepository;

  @Autowired
  private TestDatabaseCleaner cleaner;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    // Emitente completo (IE + endereco): desde a issue #88 o FiscalRulesValidator exige ambos,
    // senao a geracao falharia por validacao e nao exercitaria a concorrencia.
    Produtor completo = ProdutorTestFixtures.produtorCompleto();
    completo.setId(null);
    produtor = produtorRepository.save(completo);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    cleaner.limparTudo();
  }

  @Test
  @DisplayName("gerar XML concorrente: exatamente um request conclui e o outro recebe 409")
  void gerarXmlConcorrenteApenasUmConclui() throws Exception {
    UUID notaId = notaFiscalRepository.save(buildNotaValida()).getId();

    ResultadoConcorrente resultado = dispararGerarXmlEmParalelo(notaId);

    // Exatamente uma das duas threads emite a nota; a outra e recusada.
    assertThat(resultado.sucessos()).hasSize(1);
    assertThat(resultado.falhas()).hasSize(1);

    // A recusa e sempre um conflito 409, por um de dois caminhos legitimos:
    //  - NotaFiscalNaoEditavelException: a 2a thread leu a nota ja em XML_GERADO;
    //  - OptimisticLockingFailureException: as duas leram RASCUNHO e o @Version barrou o 2o commit.
    // Qual dos dois ocorre depende do escalonamento — ambos sao o comportamento correto, e e por
    // isso que o teste aceita os dois em vez de fixar um (o que o tornaria intermitente).
    assertThat(resultado.falhas().get(0))
        .isInstanceOfAny(
            NotaFiscalNaoEditavelException.class, OptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("gerar XML concorrente: a chave persistida corresponde ao XML persistido")
  void gerarXmlConcorrentePersisteChaveCoerenteComXml() throws Exception {
    UUID notaId = notaFiscalRepository.save(buildNotaValida()).getId();

    ResultadoConcorrente resultado = dispararGerarXmlEmParalelo(notaId);
    assertThat(resultado.sucessos()).hasSize(1);

    NotaFiscal recarregada = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(recarregada.getStatus()).isEqualTo(StatusNotaFiscal.XML_GERADO);
    assertThat(recarregada.getChaveAcesso()).isNotNull().hasSize(44);
    assertThat(recarregada.getXmlGerado()).isNotNull();

    // A asserção central da issue #95: sem @Version, a thread perdedora podia sobrescrever a chave
    // de acesso deixando o XML da outra — chave e XML divergentes na mesma linha.
    assertThat(recarregada.getXmlGerado())
        .contains("Id=\"NFe" + recarregada.getChaveAcesso() + "\"");

    // E a chave persistida e a que foi devolvida ao request vencedor.
    assertThat(resultado.sucessos().get(0).chaveAcesso())
        .isEqualTo(recarregada.getChaveAcesso());

    // A versao saiu de 0: houve exatamente um UPDATE bem-sucedido sobre a nota.
    assertThat(recarregada.getVersion()).isNotNull().isPositive();
  }

  @Test
  @DisplayName("lock otimista: o segundo save de uma versao obsoleta falha, sem depender de timing")
  void lockOtimistaRejeitaSegundaEscritaComVersaoObsoleta() {
    UUID notaId = notaFiscalRepository.save(buildNotaValida()).getId();

    // Duas leituras independentes: a classe nao e @Transactional, entao cada findById abre e
    // fecha a sua propria sessao e devolve instancias destacadas distintas — exatamente o que
    // acontece com duas requisicoes HTTP simultaneas lendo a mesma nota.
    NotaFiscal copiaA = notaFiscalRepository.findById(notaId).orElseThrow();
    NotaFiscal copiaB = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(copiaA.getVersion()).isEqualTo(copiaB.getVersion());

    // A primeira escrita vence e incrementa a versao.
    copiaA.setNaturezaOperacao("Alterado pela requisicao A");
    NotaFiscal salvaA = notaFiscalRepository.saveAndFlush(copiaA);
    assertThat(salvaA.getVersion()).isGreaterThan(copiaB.getVersion());

    // A segunda carrega a versao que ja ficou obsoleta: o UPDATE nao encontra linha e o commit
    // falha. Este teste e deterministico de proposito — o teste concorrente acima prova o
    // comportamento do fluxo, mas qual das duas excecoes de conflito ocorre lá depende do
    // escalonamento das threads; aqui o mecanismo do @Version e exercitado sempre.
    copiaB.setNaturezaOperacao("Alterado pela requisicao B");
    assertThatThrownBy(() -> notaFiscalRepository.saveAndFlush(copiaB))
        .isInstanceOf(OptimisticLockingFailureException.class);

    // E a escrita perdedora nao deixou rastro: vale o que a requisicao A gravou.
    NotaFiscal recarregada = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(recarregada.getNaturezaOperacao()).isEqualTo("Alterado pela requisicao A");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Dispara duas chamadas simultâneas de {@code gerarXml} sobre a mesma nota, alinhadas por um
   * {@link CountDownLatch} para maximizar a sobreposição, e devolve sucessos e falhas separados.
   */
  private ResultadoConcorrente dispararGerarXmlEmParalelo(UUID notaId) throws Exception {
    CountDownLatch largada = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<NotaFiscalResponse> tarefa = () -> {
        // Cada thread tem o seu SecurityContext (thread-local).
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(produtor, null, List.of()));
        largada.await(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
        try {
          return notaFiscalService.gerarXml(notaId);
        } finally {
          SecurityContextHolder.clearContext();
        }
      };

      Future<NotaFiscalResponse> primeira = executor.submit(tarefa);
      Future<NotaFiscalResponse> segunda = executor.submit(tarefa);
      largada.countDown();

      List<NotaFiscalResponse> sucessos = new ArrayList<>();
      List<Throwable> falhas = new ArrayList<>();
      for (Future<NotaFiscalResponse> future : List.of(primeira, segunda)) {
        try {
          sucessos.add(future.get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException e) {
          falhas.add(e.getCause());
        }
      }
      return new ResultadoConcorrente(sucessos, falhas);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Resultado agregado das duas threads: quem concluiu e quem foi recusado. */
  private record ResultadoConcorrente(
      List<NotaFiscalResponse> sucessos, List<Throwable> falhas) {
  }

  /** Nota em RASCUNHO completa o bastante para passar no {@code FiscalRulesValidator}. */
  private NotaFiscal buildNotaValida() {
    NotaFiscalItem item = NotaFiscalItem.builder()
        .numeroItem(1)
        .descricao("Soja em graos")
        .ncm("12010090")
        .cfop("6101")
        .unidade("KG")
        .quantidade(BigDecimal.ONE)
        .valorUnitario(new BigDecimal("1000.00"))
        .valorTotal(new BigDecimal("1000.00"))
        .build();

    NotaFiscal nota = NotaFiscal.builder()
        .numero(1L)
        .serie(1)
        .modelo("55")
        .tipoDocumento(TipoDocumentoFiscal.NFE)
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
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .status(StatusNotaFiscal.RASCUNHO)
        .build();
    nota.addItem(item);
    return nota;
  }
}
