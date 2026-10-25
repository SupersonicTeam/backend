package br.com.agrocontabil.service;

import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.NotaFiscalRevisao;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.NotaFiscalValidacaoRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de persistência e consulta do histórico de inconsistências de validação da NF-e
 * (issue #22, camada C do design da #17).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li><strong>Registrar</strong> as inconsistências encontradas ao validar/gerar o XML,
 *       reaproveitando os {@link ErrorDetail} da camada A, e marcar a nota como
 *       {@link StatusNotaFiscal#COM_INCONSISTENCIAS}.</li>
 *   <li><strong>Listar</strong> o histórico de uma nota, respeitando a propriedade do
 *       produtor autenticado (anti-IDOR 404).</li>
 * </ul>
 */
@Service
public class NotaFiscalValidacaoService {

  private static final Logger log = LoggerFactory.getLogger(NotaFiscalValidacaoService.class);

  private final NotaFiscalRepository notaFiscalRepository;
  private final NotaFiscalValidacaoRepository validacaoRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final NotaFiscalRevisaoRepository revisaoRepository;

  public NotaFiscalValidacaoService(
      NotaFiscalRepository notaFiscalRepository,
      NotaFiscalValidacaoRepository validacaoRepository,
      AuthenticatedProdutorProvider produtorProvider,
      NotaFiscalRevisaoRepository revisaoRepository) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.validacaoRepository = validacaoRepository;
    this.produtorProvider = produtorProvider;
    this.revisaoRepository = revisaoRepository;
  }

  /**
   * Registra um lote de inconsistências para a nota e a marca como
   * {@link StatusNotaFiscal#COM_INCONSISTENCIAS}.
   *
   * <p><strong>INVARIANTE: este método só pode ser chamado fora de transação</strong> (issue #97).
   *
   * <p>Ele era {@link Propagation#REQUIRES_NEW} porque o fluxo de geração de XML o chamava de
   * dentro da sua própria transação e depois relançava o 422, fazendo rollback — e o histórico
   * precisava sobreviver a esse rollback. O preço era uma <strong>segunda conexão</strong> exigida
   * enquanto a primeira seguia presa: dez requisições concorrentes de nota inválida travavam o pool
   * inteiro, cada uma esperando por uma conexão que as outras não soltavam.
   *
   * <p>Desde a issue #99, a validação e a geração rodam <strong>fora</strong> de qualquer transação
   * — não existe mais rollback a sobreviver, e {@code REQUIRED} abre uma transação própria, única,
   * que commita normalmente. A garantia da issue #22 (o histórico e o status
   * {@link StatusNotaFiscal#COM_INCONSISTENCIAS} persistem apesar do 422 devolvido ao cliente)
   * continua valendo, só que por um caminho mais simples.
   *
   * <p>Se um chamador futuro invocar este método de <em>dentro</em> de uma transação, o
   * {@code REQUIRED} se juntará a ela e o histórico voltará a morrer no rollback — regressão
   * silenciosa da #22, sem erro nem log. Nesse caso, extraia a chamada para fora da transação (como
   * {@code NotaFiscalService.gerarXml} faz) em vez de reintroduzir {@code REQUIRES_NEW}.
   *
   * <p>Não verifica propriedade: a checagem anti-IDOR já ocorreu no chamador, que carregou a
   * nota via {@code findByIdAndProdutorId}. Aqui a nota é recarregada pelo ID para ser
   * gerenciada nesta transação.
   *
   * @param notaId identificador da nota fiscal cujas inconsistências serão registradas
   * @param errors inconsistências encontradas (ErrorDetail da camada A); se vazio, nada é feito
   */
  @Transactional
  public void registrarInconsistencias(UUID notaId, List<ErrorDetail> errors) {
    if (errors == null || errors.isEmpty()) {
      return;
    }

    NotaFiscal nota = notaFiscalRepository
        .findById(notaId)
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

    UUID lote = UUID.randomUUID();
    OffsetDateTime validadoEm = OffsetDateTime.now();

    List<NotaFiscalValidacao> registros = errors.stream()
        .map(error -> NotaFiscalValidacao.builder()
            .notaFiscal(nota)
            .loteValidacao(lote)
            .code(error.code())
            .field(error.field())
            .message(error.message())
            .suggestion(error.suggestion())
            .rejectedValue(error.rejectedValue())
            .expectedValue(error.expectedValue())
            .validadoEm(validadoEm)
            .build())
        .toList();

    validacaoRepository.saveAll(registros);

    // Nota aprovada que falhou no gerar-xml: a aprovacao cai (issue #178). O contador aprovou o
    // conteudo que agora se mostrou invalido; corrigido, ele precisa ver de novo.
    if (nota.getStatus() == StatusNotaFiscal.APROVADA) {
      revisaoRepository.save(NotaFiscalRevisao.builder()
          .notaFiscalId(notaId)
          .acao(AcaoRevisao.APROVACAO_CADUCADA)
          .ocorridoEm(validadoEm)
          .build());
    }
    nota.setStatus(StatusNotaFiscal.COM_INCONSISTENCIAS);
    notaFiscalRepository.save(nota);

    log.info("Inconsistencias de validacao registradas: notaId={} lote={} quantidade={}",
        notaId, lote, registros.size());
  }

  /**
   * Lista o histórico de inconsistências de validação de uma nota, da tentativa mais recente
   * para a mais antiga.
   *
   * <p>Ownership anti-IDOR: a nota é buscada via {@code findByIdAndProdutorId} — retorna 404
   * tanto se não existir quanto se pertencer a outro produtor, sem revelar sua existência.
   *
   * @param notaId identificador da nota fiscal
   * @return histórico de inconsistências (lista vazia se a nota nunca teve inconsistências)
   * @throws ResourceNotFoundException se a nota não existir ou pertencer a outro produtor
   */
  @Transactional(readOnly = true)
  public List<NotaFiscalValidacaoResponse> listarValidacoes(UUID notaId) {
    return listarValidacoesDoProdutor(produtorProvider.get().getId(), notaId);
  }

  /**
   * Histórico de uma nota de produtor já autorizado por quem chama (leitura do contador, #177).
   *
   * @param produtorId produtor cujo acesso já foi conferido
   * @param notaId     nota
   * @return histórico de inconsistências
   * @throws ResourceNotFoundException se a nota nao for daquele produtor (404)
   */
  @Transactional(readOnly = true)
  public List<NotaFiscalValidacaoResponse> listarValidacoesDoProdutor(
      UUID produtorId, UUID notaId) {
    notaFiscalRepository
        .findByIdAndProdutorId(notaId, produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

    return NotaFiscalMapper.toValidacaoResponseList(
        validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId));
  }
}
