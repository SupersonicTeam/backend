package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.support.AutenticacaoTestSupport;
import br.com.agrocontabil.ProdutorTestFixtures;
import br.com.agrocontabil.dto.FiltroCarteiraRequest;
import br.com.agrocontabil.dto.NotaFiscalRevisaoResponse;
import br.com.agrocontabil.exception.AprovacaoExigidaException;
import br.com.agrocontabil.exception.FuncaoNaoAutorizadaException;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.exception.NotaFiscalNaoEditavelException;
import br.com.agrocontabil.exception.PapelNaoAutorizadoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.TransicaoRevisaoInvalidaException;
import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.VinculoCarteira;
import br.com.agrocontabil.repository.ContadorRepository;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aprovação do contador antes da emissão (issue #178), com o pipeline fiscal real.
 *
 * <p>Cenário: produtor A atendido pelo escritório E (administrador {@code admin}, assistente
 * {@code assistente}); produtor B sem contador; escritório E2 ({@code outro}) sem A na carteira.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("Revisao da nota: enviar, aprovar, devolver e emitir (#178)")
class RevisaoNotaIntegrationTest {

  private static final AtomicLong NUMEROS = new AtomicLong(System.nanoTime() % 100_000_000L);

  @Autowired private RevisaoNotaService revisaoService;
  @Autowired private NotaFiscalService notaFiscalService;
  @Autowired private CarteiraService carteiraService;
  @Autowired private CarteiraNotasService carteiraNotasService;
  @Autowired private NotaFiscalRepository notaRepository;
  @Autowired private NotaFiscalValidacaoRepository validacaoRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ContadorRepository contadorRepository;
  @Autowired private EscritorioRepository escritorioRepository;
  @Autowired private VinculoCarteiraRepository vinculoRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtorA;
  private Produtor produtorB;
  private Contador admin;
  private Contador assistente;
  private Contador outro;

  @BeforeEach
  void setUp() {
    transactionTemplate.executeWithoutResult(status -> {
      cleaner.limparTudo();
      Produtor a = ProdutorTestFixtures.produtorCompleto();
      a.setId(null);
      produtorA = produtorRepository.save(a);
      Produtor b = ProdutorTestFixtures.produtorCompleto();
      b.setId(null);
      b.setCpfCnpj("39053344705");
      produtorB = produtorRepository.save(b);
      Escritorio escritorio = escritorioRepository.save(Escritorio.builder().nome("E").build());
      Escritorio escritorio2 = escritorioRepository.save(Escritorio.builder().nome("E2").build());
      admin = membro(escritorio, FuncaoEscritorio.ADMINISTRADOR, "11222333000181");
      assistente = membro(escritorio, FuncaoEscritorio.ASSISTENTE, "11444777000161");
      outro = membro(escritorio2, FuncaoEscritorio.ADMINISTRADOR, "45723174000110");
      vinculoRepository.save(VinculoCarteira.builder().escritorioId(escritorio.getId())
          .produtorId(produtorA.getId()).situacao(SituacaoVinculo.ATIVO)
          .inicio(OffsetDateTime.now()).build());
    });
  }

  @AfterEach
  void tearDown() {
    AutenticacaoTestSupport.limpar();
  }

  private Contador membro(Escritorio escritorio, FuncaoEscritorio funcao, String cnpj) {
    return contadorRepository.save(Contador.builder().nomeCompleto(funcao.name()).cpfCnpj(cnpj)
        .escritorioId(escritorio.getId()).funcao(funcao).build());
  }

  private UUID nota(Produtor dono) {
    NotaFiscal nota = NotaFiscal.builder()
        .numero(NUMEROS.incrementAndGet()).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(dono)
        .destinatarioNome("Comprador Teste")
        .destinatarioCpfCnpj("98765432100")
        .destinatarioEndereco(EnderecoDestinatario.builder().logradouro("Rua das Acacias")
            .numero("250").municipio("Uberaba").codigoMunicipioIbge("3170206").uf("MG")
            .cep("38100000").build())
        .status(StatusNotaFiscal.RASCUNHO)
        .build();
    BigDecimal valor = new BigDecimal("1000.00");
    nota.addItem(NotaFiscalItem.builder().numeroItem(1).descricao("Soja em graos")
        .ncm("12010090").cfop("6101").unidade("KG").quantidade(BigDecimal.ONE)
        .valorUnitario(valor).valorTotal(valor).build());
    nota.setValorTotalProdutos(valor);
    nota.setValorTotalNota(valor);
    return transactionTemplate.execute(status -> notaRepository.save(nota)).getId();
  }

  private StatusNotaFiscal status(UUID notaId) {
    return notaRepository.findById(notaId).orElseThrow().getStatus();
  }

  private void comoProdutor(Produtor produtor) {
    AutenticacaoTestSupport.autenticarComo(produtor);
  }

  private void comoContador(Contador contador) {
    AutenticacaoTestSupport.autenticarComo(contador);
  }

  @Test
  @DisplayName("produtor sem contador emite direto e nao tem para quem enviar")
  void semContadorEmiteDireto() {
    UUID notaId = nota(produtorB);
    comoProdutor(produtorB);

    assertThatThrownBy(() -> revisaoService.enviarParaRevisao(notaId))
        .isInstanceOf(TransicaoRevisaoInvalidaException.class);
    notaFiscalService.gerarXml(notaId);

    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.XML_GERADO);
  }

  @Test
  @DisplayName("com contador: rascunho nao emite; envia, contador aprova, emite; historico")
  void cicloAprovado() {
    UUID notaId = nota(produtorA);
    comoProdutor(produtorA);
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(AprovacaoExigidaException.class);

    revisaoService.enviarParaRevisao(notaId);
    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.AGUARDANDO_APROVACAO);
    assertThatThrownBy(() -> notaFiscalService.delete(notaId))
        .isInstanceOf(NotaFiscalNaoEditavelException.class);

    comoContador(admin);
    assertThat(carteiraService.listar(new FiltroCarteiraRequest(null, null, null, null, null,
        null, null, null, null, null, null)).getContent().get(0).atencao()
        .notasAguardandoAprovacao()).isEqualTo(1);
    assertThat(carteiraNotasService.previewDanfe(produtorA.getId(), notaId)).isNotEmpty();
    revisaoService.aprovar(notaId, produtorA.getId());

    comoProdutor(produtorA);
    notaFiscalService.gerarXml(notaId);
    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.XML_GERADO);
    assertThat(revisaoService.historico(notaId)).extracting(NotaFiscalRevisaoResponse::acao)
        .containsExactly(AcaoRevisao.APROVADA, AcaoRevisao.ENVIADA);
  }

  @Test
  @DisplayName("devolucao com motivo volta a ser editavel e reenviavel; motivo no historico")
  void devolucao() {
    UUID notaId = nota(produtorA);
    comoProdutor(produtorA);
    revisaoService.enviarParaRevisao(notaId);

    comoContador(admin);
    revisaoService.devolver(notaId, produtorA.getId(), "CFOP errado para venda interna");
    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.DEVOLVIDA);

    comoProdutor(produtorA);
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(AprovacaoExigidaException.class);
    revisaoService.enviarParaRevisao(notaId);
    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.AGUARDANDO_APROVACAO);

    comoContador(assistente);
    List<NotaFiscalRevisaoResponse> historico =
        revisaoService.historicoNaCarteira(produtorA.getId(), notaId);
    assertThat(historico).extracting(NotaFiscalRevisaoResponse::acao)
        .containsExactly(AcaoRevisao.ENVIADA, AcaoRevisao.DEVOLVIDA, AcaoRevisao.ENVIADA);
    assertThat(historico.get(1).motivo()).isEqualTo("CFOP errado para venda interna");
    assertThat(historico.get(1).papel()).isEqualTo("CONTADOR");
  }

  @Test
  @DisplayName("assistente nao aprova (403); outro escritorio, nota de B ou produtor: negado")
  void quemAprova() {
    UUID notaA = nota(produtorA);
    UUID notaB = nota(produtorB);
    comoProdutor(produtorA);
    revisaoService.enviarParaRevisao(notaA);

    comoContador(assistente);
    assertThatThrownBy(() -> revisaoService.aprovar(notaA, produtorA.getId()))
        .isInstanceOf(FuncaoNaoAutorizadaException.class);
    comoContador(outro);
    assertThatThrownBy(() -> revisaoService.aprovar(notaA, produtorA.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
    comoContador(admin);
    // Nota de B pelo path de A: nao existe para o escritorio.
    assertThatThrownBy(() -> revisaoService.aprovar(notaB, produtorA.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> revisaoService.aprovar(notaB, produtorB.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
    comoProdutor(produtorA);
    assertThatThrownBy(() -> revisaoService.aprovar(notaA, produtorA.getId()))
        .isInstanceOf(PapelNaoAutorizadoException.class);
    // B nao ve o historico de A.
    comoProdutor(produtorB);
    assertThatThrownBy(() -> revisaoService.historico(notaA))
        .isInstanceOf(ResourceNotFoundException.class);

    assertThat(status(notaA)).isEqualTo(StatusNotaFiscal.AGUARDANDO_APROVACAO);
  }

  @Test
  @DisplayName("retirar volta a rascunho; aprovar depois disso e transicao invalida")
  void retirar() {
    UUID notaId = nota(produtorA);
    comoProdutor(produtorA);
    revisaoService.enviarParaRevisao(notaId);
    revisaoService.retirarDaRevisao(notaId);
    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.RASCUNHO);

    comoContador(admin);
    assertThatThrownBy(() -> revisaoService.aprovar(notaId, produtorA.getId()))
        .isInstanceOf(TransicaoRevisaoInvalidaException.class);
  }

  @Test
  @DisplayName("envio roda a validacao fiscal: nota invalida fica COM_INCONSISTENCIAS (422)")
  void envioValida() {
    UUID notaId = nota(produtorA);
    transactionTemplate.executeWithoutResult(status ->
        notaRepository.findById(notaId).orElseThrow().setDestinatarioEndereco(null));
    comoProdutor(produtorA);

    assertThatThrownBy(() -> revisaoService.enviarParaRevisao(notaId))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);

    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
    assertThat(validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId)).isNotEmpty();
  }

  @Test
  @DisplayName("gerar-xml falhando numa nota aprovada derruba a aprovacao")
  void aprovacaoCaduca() {
    UUID notaId = nota(produtorA);
    comoProdutor(produtorA);
    revisaoService.enviarParaRevisao(notaId);
    comoContador(admin);
    revisaoService.aprovar(notaId, produtorA.getId());
    transactionTemplate.executeWithoutResult(status ->
        notaRepository.findById(notaId).orElseThrow().setDestinatarioEndereco(null));

    comoProdutor(produtorA);
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);

    assertThat(status(notaId)).isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
    assertThat(revisaoService.historico(notaId)).extracting(NotaFiscalRevisaoResponse::acao)
        .first().isEqualTo(AcaoRevisao.APROVACAO_CADUCADA);
  }
}
