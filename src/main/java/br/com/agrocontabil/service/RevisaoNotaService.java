package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalRevisaoResponse;
import br.com.agrocontabil.exception.FuncaoNaoAutorizadaException;
import br.com.agrocontabil.exception.NfaInvalidaParaEmissaoException;
import br.com.agrocontabil.exception.NotaFiscalInvalidaParaEmissaoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.exception.TransicaoRevisaoInvalidaException;
import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.AcaoRevisao;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalRevisao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import br.com.agrocontabil.nfe.FiscalRulesValidator;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.repository.NotaFiscalRevisaoRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Revisão da nota pelo contador antes da emissão (issue #178).
 *
 * <pre>
 * RASCUNHO | COM_INCONSISTENCIAS | DEVOLVIDA --enviar (produtor)--> AGUARDANDO_APROVACAO
 * AGUARDANDO_APROVACAO --retirar (produtor)--> RASCUNHO
 * AGUARDANDO_APROVACAO --aprovar (contador)--> APROVADA --gerar-xml--> XML_GERADO
 * AGUARDANDO_APROVACAO --devolver {motivo} (contador)--> DEVOLVIDA
 * APROVADA --gerar-xml com inconsistencia--> COM_INCONSISTENCIAS (aprovacao caduca)
 * </pre>
 *
 * <p>O envio exige escritório vinculado e roda a validação fiscal antes, <strong>fora de
 * transação</strong> (o padrão do {@code gerar-xml}, #99): inconsistência registra o lote em
 * {@code nota_fiscal_validacao}, marca {@code COM_INCONSISTENCIAS} e responde 422. As transições
 * gravam sob o lock pessimista da nota e rechecam o status, então aprovar e retirar ao mesmo tempo
 * não passam juntos. O contador não edita: só aprova ou devolve, e aprova ou devolve o
 * {@code ADMINISTRADOR} ou o {@code CONTADOR} do escritório — o {@code ASSISTENTE} recebe 403.
 */
@Service
public class RevisaoNotaService {

  private static final Set<StatusNotaFiscal> ENVIAVEIS = NotaFiscalService.STATUS_EDITAVEIS;
  private static final Set<StatusNotaFiscal> EM_REVISAO =
      EnumSet.of(StatusNotaFiscal.AGUARDANDO_APROVACAO);

  private final NotaFiscalRepository notaRepository;
  private final NotaFiscalRevisaoRepository revisaoRepository;
  private final VinculoCarteiraRepository vinculoRepository;
  private final NotaFiscalValidacaoService validacaoService;
  private final FiscalRulesValidator fiscalRulesValidator;
  private final GeracaoNfaService geracaoNfaService;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final UsuarioAutenticadoProvider usuarioProvider;
  private final MembroEscritorioProvider membroProvider;
  private final ProdutorEmContextoResolver produtorEmContexto;
  private final Clock clock;
  private final TransactionTemplate transacao;
  private final TransactionTemplate leitura;

  public RevisaoNotaService(
      NotaFiscalRepository notaRepository,
      NotaFiscalRevisaoRepository revisaoRepository,
      VinculoCarteiraRepository vinculoRepository,
      NotaFiscalValidacaoService validacaoService,
      FiscalRulesValidator fiscalRulesValidator,
      GeracaoNfaService geracaoNfaService,
      AuthenticatedProdutorProvider produtorProvider,
      UsuarioAutenticadoProvider usuarioProvider,
      MembroEscritorioProvider membroProvider,
      ProdutorEmContextoResolver produtorEmContexto,
      Clock clock,
      TransactionTemplate transactionTemplate) {
    this.notaRepository = notaRepository;
    this.revisaoRepository = revisaoRepository;
    this.vinculoRepository = vinculoRepository;
    this.validacaoService = validacaoService;
    this.fiscalRulesValidator = fiscalRulesValidator;
    this.geracaoNfaService = geracaoNfaService;
    this.produtorProvider = produtorProvider;
    this.usuarioProvider = usuarioProvider;
    this.membroProvider = membroProvider;
    this.produtorEmContexto = produtorEmContexto;
    this.clock = clock;
    this.transacao = transactionTemplate;
    TransactionTemplate somenteLeitura = new TransactionTemplate(
        transactionTemplate.getTransactionManager(), transactionTemplate);
    somenteLeitura.setReadOnly(true);
    this.leitura = somenteLeitura;
  }

  /**
   * O produtor envia a nota para o contador revisar, depois da validação fiscal.
   *
   * @param notaId nota
   * @return a nota em {@code AGUARDANDO_APROVACAO}
   * @throws TransicaoRevisaoInvalidaException sem contador vinculado, ou status fora de
   *     rascunho/inconsistências/devolvida (409)
   * @throws NotaFiscalInvalidaParaEmissaoException validação fiscal falhou (422; a nota fica
   *     {@code COM_INCONSISTENCIAS} com o lote registrado)
   */
  @Auditado(acao = AcaoAuditada.NOTA_ENVIADA_REVISAO, recurso = RecursoAuditado.NOTA_FISCAL)
  public NotaFiscalResponse enviarParaRevisao(UUID notaId) {
    Produtor produtor = produtorProvider.get();
    if (vinculoRepository.findEscritoriosAtivosDoProdutor(produtor.getId()).isEmpty()) {
      throw TransicaoRevisaoInvalidaException.semContador();
    }
    NotaFiscal nota = emLeitura(() -> {
      NotaFiscal encontrada = notaRepository
          .findByIdAndProdutorIdComGrafoDeEmissao(notaId, produtor.getId())
          .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));
      exigirStatus(encontrada, ENVIAVEIS);
      return encontrada;
    });

    // Fora de transacao, como no gerar-xml: o registro das inconsistencias abre a propria.
    try {
      if (nota.getTipoDocumento() == TipoDocumentoFiscal.NFA) {
        geracaoNfaService.validarPreCondicoes(nota);
      }
      fiscalRulesValidator.validar(nota);
    } catch (NfaInvalidaParaEmissaoException | NotaFiscalInvalidaParaEmissaoException e) {
      validacaoService.registrarInconsistencias(nota.getId(), e.getErrors());
      throw e;
    }

    Long versaoLida = nota.getVersion();
    return emTransacao(() -> {
      NotaFiscal atual = travada(notaId, produtor.getId());
      exigirStatus(atual, ENVIAVEIS);
      if (!Objects.equals(versaoLida, atual.getVersion())) {
        // Editada durante a validacao: o que foi validado nao e mais o que esta gravado.
        throw new OptimisticLockingFailureException(
            "NotaFiscal " + notaId + " foi alterada durante o envio para revisao");
      }
      return transitar(atual, StatusNotaFiscal.AGUARDANDO_APROVACAO, AcaoRevisao.ENVIADA, null);
    });
  }

  /**
   * O produtor retira a nota da revisão: volta a rascunho (ex.: o contador saiu da carteira).
   *
   * @param notaId nota
   * @return a nota em {@code RASCUNHO}
   */
  @Auditado(acao = AcaoAuditada.NOTA_RETIRADA_REVISAO, recurso = RecursoAuditado.NOTA_FISCAL)
  public NotaFiscalResponse retirarDaRevisao(UUID notaId) {
    UUID produtorId = produtorProvider.get().getId();
    return emTransacao(() -> {
      NotaFiscal atual = travada(notaId, produtorId);
      exigirStatus(atual, EM_REVISAO);
      return transitar(atual, StatusNotaFiscal.RASCUNHO, AcaoRevisao.RETIRADA, null);
    });
  }

  /**
   * O contador aprova a nota (administrador ou contador do escritório).
   *
   * @param notaId     nota
   * @param produtorId cliente da carteira ativa
   * @return a nota em {@code APROVADA}
   * @throws ResourceNotFoundException fora da carteira ativa, ou nota de outro produtor (404)
   * @throws FuncaoNaoAutorizadaException assistente (403)
   */
  @Auditado(acao = AcaoAuditada.NOTA_APROVADA, recurso = RecursoAuditado.NOTA_FISCAL)
  public NotaFiscalResponse aprovar(UUID notaId, UUID produtorId) {
    exigirRevisor(produtorId);
    return emTransacao(() -> {
      NotaFiscal atual = travada(notaId, produtorId);
      exigirStatus(atual, EM_REVISAO);
      return transitar(atual, StatusNotaFiscal.APROVADA, AcaoRevisao.APROVADA, null);
    });
  }

  /**
   * O contador devolve a nota com motivo (administrador ou contador do escritório).
   *
   * @param notaId     nota
   * @param produtorId cliente da carteira ativa
   * @param motivo     o que corrigir
   * @return a nota em {@code DEVOLVIDA}
   */
  @Auditado(acao = AcaoAuditada.NOTA_DEVOLVIDA, recurso = RecursoAuditado.NOTA_FISCAL)
  public NotaFiscalResponse devolver(UUID notaId, UUID produtorId, String motivo) {
    exigirRevisor(produtorId);
    return emTransacao(() -> {
      NotaFiscal atual = travada(notaId, produtorId);
      exigirStatus(atual, EM_REVISAO);
      return transitar(atual, StatusNotaFiscal.DEVOLVIDA, AcaoRevisao.DEVOLVIDA, motivo.strip());
    });
  }

  /**
   * Histórico de revisão de uma nota do produtor autenticado.
   *
   * @param notaId nota
   * @return ações, da mais recente à mais antiga
   */
  public List<NotaFiscalRevisaoResponse> historico(UUID notaId) {
    return historicoDoProdutor(produtorProvider.get().getId(), notaId);
  }

  /**
   * Histórico de revisão de uma nota de cliente da carteira (qualquer membro).
   *
   * @param produtorId cliente
   * @param notaId     nota
   * @return ações, da mais recente à mais antiga
   */
  public List<NotaFiscalRevisaoResponse> historicoNaCarteira(UUID produtorId, UUID notaId) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return historicoDoProdutor(produtorId, notaId);
  }

  private List<NotaFiscalRevisaoResponse> historicoDoProdutor(UUID produtorId, UUID notaId) {
    return emLeitura(() -> {
      notaRepository.findByIdAndProdutorId(notaId, produtorId)
          .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));
      return revisaoRepository.findByNotaFiscalIdOrderByOcorridoEmDesc(notaId).stream()
          .map(r -> new NotaFiscalRevisaoResponse(r.getId(), r.getAcao(), r.getMotivo(),
              r.getUsuarioId(), r.getPapel(), r.getOcorridoEm()))
          .toList();
    });
  }

  /** Carteira ativa primeiro (404), depois a função (403): o assistente não aprova nem devolve. */
  private void exigirRevisor(UUID produtorId) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR, FuncaoEscritorio.CONTADOR);
  }

  private NotaFiscal travada(UUID notaId, UUID produtorId) {
    return notaRepository.findByIdAndProdutorIdForUpdate(notaId, produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", notaId));
  }

  private static void exigirStatus(NotaFiscal nota, Set<StatusNotaFiscal> permitidos) {
    if (!permitidos.contains(nota.getStatus())) {
      throw TransicaoRevisaoInvalidaException.status(nota.getId(), nota.getStatus(), permitidos);
    }
  }

  private NotaFiscalResponse transitar(NotaFiscal nota, StatusNotaFiscal novo, AcaoRevisao acao,
      String motivo) {
    UsuarioAutenticado usuario = usuarioProvider.get();
    nota.setStatus(novo);
    revisaoRepository.save(NotaFiscalRevisao.builder()
        .notaFiscalId(nota.getId())
        .acao(acao)
        .motivo(motivo)
        .usuarioId(usuario.id())
        .papel(usuario.papel().name())
        .ocorridoEm(OffsetDateTime.now(clock))
        .build());
    return NotaFiscalMapper.toResponse(notaRepository.save(nota));
  }

  private <T> T emTransacao(Supplier<T> acao) {
    return transacao.execute(status -> acao.get());
  }

  private <T> T emLeitura(Supplier<T> acao) {
    return leitura.execute(status -> acao.get());
  }
}
