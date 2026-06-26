package br.com.agrocontabil.service;

import br.com.agrocontabil.common.ErrorDetail;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
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

  public NotaFiscalValidacaoService(
      NotaFiscalRepository notaFiscalRepository,
      NotaFiscalValidacaoRepository validacaoRepository,
      AuthenticatedProdutorProvider produtorProvider) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.validacaoRepository = validacaoRepository;
    this.produtorProvider = produtorProvider;
  }

  /**
   * Registra um lote de inconsistências para a nota e a marca como
   * {@link StatusNotaFiscal#COM_INCONSISTENCIAS}.
   *
   * <p><strong>Transação independente ({@link Propagation#REQUIRES_NEW}):</strong> este método
   * é chamado de dentro do fluxo de geração de XML, que relança a exceção de validação (HTTP
   * 422) e, portanto, faz rollback da sua própria transação. Persistir o histórico numa
   * transação separada garante que o registro das inconsistências sobreviva a esse rollback.
   *
   * <p>Não verifica propriedade: a checagem anti-IDOR já ocorreu no chamador, que carregou a
   * nota via {@code findByIdAndProdutorId}. Aqui a nota é recarregada pelo ID para ser
   * gerenciada na nova transação.
   *
   * @param notaId identificador da nota fiscal cujas inconsistências serão registradas
   * @param errors inconsistências encontradas (ErrorDetail da camada A); se vazio, nada é feito
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
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
    Produtor produtor = produtorProvider.get();
    notaFiscalRepository
        .findByIdAndProdutorId(notaId, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));

    return NotaFiscalMapper.toValidacaoResponseList(
        validacaoRepository.findByNotaFiscalIdOrderByValidadoEmDesc(notaId));
  }
}
