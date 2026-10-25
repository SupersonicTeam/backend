package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.PendenciaRequest;
import br.com.agrocontabil.dto.PendenciaResponse;
import br.com.agrocontabil.exception.PendenciaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Pendencia;
import br.com.agrocontabil.model.PendenciaAnexo;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.model.StatusPendencia;
import br.com.agrocontabil.repository.AnexoResumo;
import br.com.agrocontabil.repository.ContagemPorPendencia;
import br.com.agrocontabil.repository.PendenciaAnexoRepository;
import br.com.agrocontabil.repository.PendenciaRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pendências e documentos (issue #180, versão mínima): o escritório pede, o produtor anexa.
 *
 * <p>Lado do escritório em {@code /api/contador/produtores/{produtorId}/pendencias/...} —
 * qualquer membro, só a carteira ativa (404 fora); lado do produtor em {@code /api/pendencias},
 * só as dele (404 para a de outro). As transições gravam sob o lock pessimista da pendência e
 * rechecam o status. O anexo é conferido pelos bytes (PDF/JPEG/PNG/XML, até 5 MB, até 10 por
 * pendência) e fica no banco; o download devolve o MIME conferido, nunca o declarado.
 */
@Service
public class PendenciaService {

  /** Teto de anexos por pendência. */
  static final int MAXIMO_ANEXOS = 10;

  private static final Set<StatusPendencia> TODOS = EnumSet.allOf(StatusPendencia.class);

  private final ProdutorEmContextoResolver produtorEmContexto;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final PendenciaRepository pendenciaRepository;
  private final PendenciaAnexoRepository anexoRepository;
  private final Clock clock;

  public PendenciaService(ProdutorEmContextoResolver produtorEmContexto,
      AuthenticatedProdutorProvider produtorProvider, PendenciaRepository pendenciaRepository,
      PendenciaAnexoRepository anexoRepository, Clock clock) {
    this.produtorEmContexto = produtorEmContexto;
    this.produtorProvider = produtorProvider;
    this.pendenciaRepository = pendenciaRepository;
    this.anexoRepository = anexoRepository;
    this.clock = clock;
  }

  /** Arquivo para download, com o MIME conferido no envio. */
  public record Download(String nomeArquivo, String mime, byte[] conteudo) {
  }

  // ---------------------------------------------------------------------------------------------
  // Escritorio
  // ---------------------------------------------------------------------------------------------

  /**
   * Pendências do cliente pedidas pelo escritório do contador autenticado.
   *
   * @param produtorId cliente
   * @param status     filtro (vazio = todas)
   * @return pendências, mais recentes primeiro
   */
  @Transactional(readOnly = true)
  public List<PendenciaResponse> listar(UUID produtorId, Collection<StatusPendencia> status) {
    UUID escritorioId = escritorio(produtorId);
    return lista(pendenciaRepository.findDoEscritorio(escritorioId, produtorId, filtro(status)));
  }

  /**
   * Uma pendência do cliente, com os anexos.
   *
   * @param produtorId  cliente
   * @param pendenciaId pendência
   * @return a pendência
   */
  @Transactional(readOnly = true)
  public PendenciaResponse detalhar(UUID produtorId, UUID pendenciaId) {
    return resposta(doEscritorio(pendenciaId, produtorId), true);
  }

  /**
   * O escritório pede documentos ao cliente.
   *
   * @param produtorId cliente
   * @param request    o pedido
   * @return a pendência em {@code PENDENTE}
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.PENDENCIA_CRIADA, recurso = RecursoAuditado.PENDENCIA)
  public PendenciaResponse criar(UUID produtorId, PendenciaRequest request) {
    Contador membro = produtorEmContexto.exigirContadorComAcesso(produtorId).contador();
    Pendencia pendencia = Pendencia.builder()
        .escritorioId(membro.getEscritorioId())
        .produtorId(produtorId)
        .status(StatusPendencia.PENDENTE)
        .criadoPor(membro.getId())
        .build();
    aplicar(pendencia, request);
    return resposta(pendenciaRepository.save(pendencia), false);
  }

  /**
   * Edita o pedido enquanto o produtor ainda não enviou nada ({@code PENDENTE}).
   *
   * @param pendenciaId pendência
   * @param produtorId  cliente
   * @param request     o pedido
   * @return a pendência
   */
  @Transactional
  public PendenciaResponse editar(UUID pendenciaId, UUID produtorId, PendenciaRequest request) {
    Pendencia pendencia = travadaDoEscritorio(pendenciaId, produtorId);
    exigir(pendencia, EnumSet.of(StatusPendencia.PENDENTE));
    aplicar(pendencia, request);
    return resposta(pendencia, false);
  }

  /**
   * Aceita o que o produtor enviou.
   *
   * @param pendenciaId pendência
   * @param produtorId  cliente
   * @return a pendência em {@code CONCLUIDA}
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.PENDENCIA_CONCLUIDA, recurso = RecursoAuditado.PENDENCIA)
  public PendenciaResponse concluir(UUID pendenciaId, UUID produtorId) {
    Pendencia pendencia = travadaDoEscritorio(pendenciaId, produtorId);
    exigir(pendencia, EnumSet.of(StatusPendencia.ENVIADA));
    pendencia.setStatus(StatusPendencia.CONCLUIDA);
    return resposta(pendencia, false);
  }

  /**
   * Recusa o que foi enviado: volta a {@code PENDENTE}, os anexos ficam e o motivo é guardado.
   *
   * @param pendenciaId pendência
   * @param produtorId  cliente
   * @param motivo      o que falta ou está errado
   * @return a pendência em {@code PENDENTE}
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.PENDENCIA_REJEITADA, recurso = RecursoAuditado.PENDENCIA)
  public PendenciaResponse rejeitar(UUID pendenciaId, UUID produtorId, String motivo) {
    Pendencia pendencia = travadaDoEscritorio(pendenciaId, produtorId);
    exigir(pendencia, EnumSet.of(StatusPendencia.ENVIADA));
    pendencia.setStatus(StatusPendencia.PENDENTE);
    pendencia.setMotivo(motivo);
    return resposta(pendencia, false);
  }

  /**
   * Cancela o pedido.
   *
   * @param pendenciaId pendência
   * @param produtorId  cliente
   * @param motivo      por que não é mais preciso
   * @return a pendência em {@code CANCELADA}
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.PENDENCIA_CANCELADA, recurso = RecursoAuditado.PENDENCIA)
  public PendenciaResponse cancelar(UUID pendenciaId, UUID produtorId, String motivo) {
    Pendencia pendencia = travadaDoEscritorio(pendenciaId, produtorId);
    exigir(pendencia, EnumSet.of(StatusPendencia.PENDENTE, StatusPendencia.ENVIADA));
    pendencia.setStatus(StatusPendencia.CANCELADA);
    pendencia.setMotivo(motivo);
    return resposta(pendencia, false);
  }

  /**
   * Reabre um pedido concluído ou cancelado.
   *
   * @param pendenciaId pendência
   * @param produtorId  cliente
   * @return a pendência em {@code PENDENTE}
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.PENDENCIA_REABERTA, recurso = RecursoAuditado.PENDENCIA)
  public PendenciaResponse reabrir(UUID pendenciaId, UUID produtorId) {
    Pendencia pendencia = travadaDoEscritorio(pendenciaId, produtorId);
    exigir(pendencia, EnumSet.of(StatusPendencia.CONCLUIDA, StatusPendencia.CANCELADA));
    pendencia.setStatus(StatusPendencia.PENDENTE);
    // O motivo era do cancelamento (ou da ultima rejeicao): reaberto, o pedido recomeca limpo.
    pendencia.setMotivo(null);
    return resposta(pendencia, false);
  }

  /**
   * Arquivo de um anexo, para o escritório.
   *
   * @param produtorId  cliente
   * @param pendenciaId pendência
   * @param anexoId     anexo
   * @return o arquivo
   */
  @Transactional(readOnly = true)
  public Download baixar(UUID produtorId, UUID pendenciaId, UUID anexoId) {
    return download(doEscritorio(pendenciaId, produtorId).getId(), anexoId);
  }

  // ---------------------------------------------------------------------------------------------
  // Produtor
  // ---------------------------------------------------------------------------------------------

  /**
   * Pendências do produtor autenticado, de todos os escritórios.
   *
   * @param status filtro (vazio = todas)
   * @return pendências, mais recentes primeiro
   */
  @Transactional(readOnly = true)
  public List<PendenciaResponse> listarDoProdutor(Collection<StatusPendencia> status) {
    return lista(pendenciaRepository.findDoProdutor(produtorProvider.get().getId(),
        filtro(status)));
  }

  /**
   * Uma pendência do produtor autenticado, com os anexos.
   *
   * @param pendenciaId pendência
   * @return a pendência (404 se for de outro produtor)
   */
  @Transactional(readOnly = true)
  public PendenciaResponse detalharDoProdutor(UUID pendenciaId) {
    return resposta(doProdutor(pendenciaId), true);
  }

  /**
   * O produtor anexa um arquivo: a pendência vai a {@code ENVIADA}. Só em escritório com vínculo
   * {@code ATIVO}; dono, status e teto são conferidos antes de o arquivo ser lido.
   *
   * @param pendenciaId  pendência
   * @param nomeOriginal nome do arquivo enviado
   * @param conteudo     bytes
   * @return a pendência, com os anexos
   * @throws PendenciaException arquivo recusado (400), teto de anexos (409) ou pendência fechada
   *     (409)
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.PENDENCIA_ANEXO_ENVIADO, recurso = RecursoAuditado.PENDENCIA)
  public PendenciaResponse anexar(UUID pendenciaId, String nomeOriginal, byte[] conteudo) {
    UUID produtorId = produtorProvider.get().getId();
    Pendencia pendencia = pendenciaRepository.findDoProdutorAtivaForUpdate(pendenciaId,
            produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Pendencia", pendenciaId));
    exigir(pendencia, EnumSet.of(StatusPendencia.PENDENTE, StatusPendencia.ENVIADA));
    if (anexoRepository.countByPendenciaId(pendenciaId) >= MAXIMO_ANEXOS) {
      throw PendenciaException.limiteAnexos(MAXIMO_ANEXOS);
    }
    String mime = ConferenciaAnexo.mimeConferido(conteudo);
    anexoRepository.save(PendenciaAnexo.builder()
        .pendenciaId(pendenciaId)
        .nomeArquivo(ConferenciaAnexo.nomeSanitizado(nomeOriginal))
        .mime(mime)
        .tamanho(conteudo.length)
        .sha256(ConferenciaAnexo.sha256(conteudo))
        .conteudo(conteudo)
        .enviadoPor(produtorId)
        .enviadoEm(OffsetDateTime.now(clock))
        .build());
    pendencia.setStatus(StatusPendencia.ENVIADA);
    return resposta(pendencia, true);
  }

  /**
   * Arquivo de um anexo, para o produtor.
   *
   * @param pendenciaId pendência
   * @param anexoId     anexo
   * @return o arquivo
   */
  @Transactional(readOnly = true)
  public Download baixarDoProdutor(UUID pendenciaId, UUID anexoId) {
    return download(doProdutor(pendenciaId).getId(), anexoId);
  }

  // ---------------------------------------------------------------------------------------------

  private UUID escritorio(UUID produtorId) {
    return produtorEmContexto.exigirContadorComAcesso(produtorId).contador().getEscritorioId();
  }

  private Pendencia doEscritorio(UUID pendenciaId, UUID produtorId) {
    UUID escritorioId = escritorio(produtorId);
    return pendenciaRepository.findById(pendenciaId)
        .filter(p -> p.getEscritorioId().equals(escritorioId))
        .filter(p -> p.getProdutorId().equals(produtorId))
        .orElseThrow(() -> new ResourceNotFoundException("Pendencia", pendenciaId));
  }

  private Pendencia travadaDoEscritorio(UUID pendenciaId, UUID produtorId) {
    UUID escritorioId = escritorio(produtorId);
    return pendenciaRepository.findDoEscritorioForUpdate(pendenciaId, escritorioId, produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Pendencia", pendenciaId));
  }

  private Pendencia doProdutor(UUID pendenciaId) {
    return pendenciaRepository.findDoProdutorAtiva(pendenciaId, produtorProvider.get().getId())
        .orElseThrow(() -> new ResourceNotFoundException("Pendencia", pendenciaId));
  }

  private Download download(UUID pendenciaId, UUID anexoId) {
    PendenciaAnexo anexo = anexoRepository.findByIdAndPendenciaId(anexoId, pendenciaId)
        .orElseThrow(() -> new ResourceNotFoundException("Anexo", anexoId));
    return new Download(anexo.getNomeArquivo(), anexo.getMime(), anexo.getConteudo());
  }

  private static void exigir(Pendencia pendencia, Set<StatusPendencia> permitidos) {
    if (!permitidos.contains(pendencia.getStatus())) {
      throw PendenciaException.transicao(pendencia.getId(), pendencia.getStatus(), permitidos);
    }
  }

  private static Set<StatusPendencia> filtro(Collection<StatusPendencia> status) {
    return status == null || status.isEmpty() ? TODOS : EnumSet.copyOf(status);
  }

  private static void aplicar(Pendencia pendencia, PendenciaRequest request) {
    pendencia.setTitulo(request.titulo().strip());
    pendencia.setDescricao(request.descricao());
    pendencia.setDataLimite(request.dataLimite());
    pendencia.setTiposDocumento(EnumSet.copyOf(request.tiposDocumento()));
    pendencia.setObservacoes(request.observacoes());
  }

  /** Lista sem os anexos, com a quantidade de cada pendência numa consulta só. */
  private List<PendenciaResponse> lista(List<Pendencia> pendencias) {
    if (pendencias.isEmpty()) {
      return List.of();
    }
    Map<UUID, Long> quantidades = anexoRepository.contarPorPendencia(
            pendencias.stream().map(Pendencia::getId).toList()).stream()
        .collect(Collectors.toMap(ContagemPorPendencia::pendenciaId, ContagemPorPendencia::total));
    return pendencias.stream()
        .map(p -> resposta(p, List.of(), quantidades.getOrDefault(p.getId(), 0L))).toList();
  }

  private PendenciaResponse resposta(Pendencia p, boolean comAnexos) {
    List<AnexoResumo> anexos = comAnexos && p.getId() != null
        ? anexoRepository.findResumos(p.getId()) : List.of();
    long quantidade = comAnexos ? anexos.size()
        : p.getId() == null ? 0 : anexoRepository.countByPendenciaId(p.getId());
    return resposta(p, anexos, quantidade);
  }

  private static PendenciaResponse resposta(Pendencia p, List<AnexoResumo> anexos,
      long quantidade) {
    return new PendenciaResponse(p.getId(), p.getProdutorId(), p.getEscritorioId(),
        p.getTitulo(), p.getDescricao(), p.getObservacoes(), p.getDataLimite(),
        List.copyOf(p.getTiposDocumento()), p.getStatus(), p.getMotivo(), quantidade, anexos,
        p.getCreatedAt(), p.getUpdatedAt());
  }
}
