package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Testes de integração do fluxo de persistência de inconsistências (issue #22).
 *
 * <p>Diferente dos testes unitários (Mockito), este sobe o contexto Spring completo e
 * exercita a transação real: ao gerar o XML de uma nota inválida, o
 * {@link NotaFiscalService} faz <em>rollback</em> da sua própria transação (HTTP 422), mas o
 * histórico de inconsistências persiste graças à transação independente
 * ({@code REQUIRES_NEW}) do {@link NotaFiscalValidacaoService}. Este é o comportamento que
 * o teste unitário não consegue provar.
 *
 * <p>A classe <strong>não</strong> usa {@code @Transactional}: cada operação commita de
 * verdade — caso contrário a interação entre a transação do teste e a {@code REQUIRES_NEW}
 * mascararia o cenário. A limpeza é feita manualmente no {@link #tearDown()}.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotaFiscalValidacaoFlowTest {

  @Autowired
  private NotaFiscalService notaFiscalService;

  @Autowired
  private NotaFiscalValidacaoService validacaoService;

  @Autowired
  private NotaFiscalRepository notaFiscalRepository;

  @Autowired
  private NotaFiscalValidacaoRepository validacaoRepository;

  @Autowired
  private ProdutorRepository produtorRepository;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = produtorRepository.save(Produtor.builder()
        .nomeCompleto("Maria Agricultora")
        .cpfCnpj("98765432100")
        // Sem estado/IE: gera violacao no emitente, alem da nota sem itens/endereco.
        .build());

    // Simula o produtor autenticado (papel do MockProdutorFilter em runtime).
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(produtor, null, List.of()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    validacaoRepository.deleteAll();
    notaFiscalRepository.deleteAll();
    produtorRepository.deleteAll();
  }

  @Test
  @DisplayName("gerar XML de nota invalida persiste o historico e marca COM_INCONSISTENCIAS apesar do rollback")
  void gerarXmlInvalidaShouldPersistHistoryDespiteRollback() {
    NotaFiscal nota = notaFiscalRepository.save(buildNotaInvalida());
    UUID notaId = nota.getId();

    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);

    // Apesar do rollback da transacao de gerarXml, o historico sobrevive (REQUIRES_NEW)
    List<NotaFiscalValidacao> registros =
        validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId);
    assertThat(registros).isNotEmpty();
    // Todas as inconsistencias da tentativa compartilham o mesmo lote
    UUID lote = registros.get(0).getLoteValidacao();
    assertThat(registros).allSatisfy(r -> assertThat(r.getLoteValidacao()).isEqualTo(lote));

    // E o status da nota foi atualizado e persistido
    NotaFiscal recarregada = notaFiscalRepository.findById(notaId).orElseThrow();
    assertThat(recarregada.getStatus()).isEqualTo(StatusNotaFiscal.COM_INCONSISTENCIAS);
  }

  @Test
  @DisplayName("listarValidacoes retorna o historico persistido apos uma geracao que falhou")
  void listarValidacoesShouldReturnPersistedHistory() {
    NotaFiscal nota = notaFiscalRepository.save(buildNotaInvalida());
    UUID notaId = nota.getId();

    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);

    List<NotaFiscalValidacaoResponse> historico = validacaoService.listarValidacoes(notaId);

    assertThat(historico).isNotEmpty();
    assertThat(historico).allSatisfy(v -> {
      assertThat(v.code()).isNotBlank();
      assertThat(v.message()).isNotBlank();
      assertThat(v.validadoEm()).isNotNull();
    });
  }

  @Test
  @DisplayName("duas geracoes que falham acumulam dois lotes distintos no historico")
  void duasFalhasDevemAcumularDoisLotes() {
    NotaFiscal nota = notaFiscalRepository.save(buildNotaInvalida());
    UUID notaId = nota.getId();

    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);
    assertThatThrownBy(() -> notaFiscalService.gerarXml(notaId))
        .isInstanceOf(NotaFiscalInvalidaParaEmissaoException.class);

    List<NotaFiscalValidacao> registros =
        validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId);
    long lotesDistintos = registros.stream()
        .map(NotaFiscalValidacao::getLoteValidacao)
        .distinct()
        .count();

    assertThat(lotesDistintos).isEqualTo(2);
  }

  /**
   * Nota persistível (satisfaz as colunas NOT NULL) mas inválida para emissão: sem itens,
   * sem endereço do destinatário e com emitente sem UF — gera múltiplas violações no
   * {@link br.com.agrocontabil.nfe.FiscalRulesValidator}.
   */
  private NotaFiscal buildNotaInvalida() {
    return NotaFiscal.builder()
        .numero(1L)
        .serie(1)
        .modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.now())
        .produtor(produtor)
        .destinatarioNome("Jose Comprador")
        .destinatarioCpfCnpj("12345678901")
        .valorTotalProdutos(BigDecimal.ZERO)
        .valorTotalNota(BigDecimal.ZERO)
        .status(StatusNotaFiscal.RASCUNHO)
        .build();
  }
}
