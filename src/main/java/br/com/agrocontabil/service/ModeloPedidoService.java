package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ModeloPedidoRequest;
import br.com.agrocontabil.dto.ModeloPedidoResponse;
import br.com.agrocontabil.exception.ModeloPedidoException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.ModeloPedidoMapper;
import br.com.agrocontabil.model.AcaoAuditada;
import br.com.agrocontabil.model.FuncaoEscritorio;
import br.com.agrocontabil.model.ModeloPedido;
import br.com.agrocontabil.model.RecursoAuditado;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.repository.ModeloPedidoRepository;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.service.auditoria.Auditado;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de modelos de pedido de documento do escritório (issue #517), no desenho dos catálogos
 * de etiquetas (#177) e de obrigações (#181).
 *
 * <p>Qualquer membro lê (e usa o modelo para preencher o pedido); só o {@code ADMINISTRADOR} cria,
 * edita e apaga. Modelo de outro escritório é 404. Título único por escritório, sem acento nem
 * caixa (409 {@code MODELO_PEDIDO_DUPLICADO}), e no máximo {@value #MAXIMO_POR_ESCRITORIO} modelos
 * (409 {@code LIMITE_MODELOS_PEDIDO}).
 *
 * <p>O modelo só preenche o formulário: o pedido continua no {@link PendenciaService}, sem
 * referência a ele, então editar ou apagar um modelo nunca muda pedido enviado.
 *
 * <p>As escritas rodam com o escritório sob lock: a contagem do teto e a checagem do título não
 * correm com outra escrita do mesmo catálogo. Os textos nunca vão a log nem à auditoria (podem
 * citar o cliente).
 */
@Service
public class ModeloPedidoService {

  /** Teto de modelos por escritório (contrato da frontend#185). */
  public static final int MAXIMO_POR_ESCRITORIO = 50;

  private final MembroEscritorioProvider membroProvider;
  private final ModeloPedidoRepository modeloRepository;
  private final EscritorioRepository escritorioRepository;

  /**
   * Cria o serviço.
   *
   * @param membroProvider       contador autenticado como membro do escritório
   * @param modeloRepository     modelos
   * @param escritorioRepository escritório (lock das escritas)
   */
  public ModeloPedidoService(MembroEscritorioProvider membroProvider,
      ModeloPedidoRepository modeloRepository, EscritorioRepository escritorioRepository) {
    this.membroProvider = membroProvider;
    this.modeloRepository = modeloRepository;
    this.escritorioRepository = escritorioRepository;
  }

  /**
   * Modelos do escritório do contador autenticado (qualquer membro).
   *
   * @return modelos, na ordem do título sem acento nem caixa
   */
  @Transactional(readOnly = true)
  public List<ModeloPedidoResponse> listar() {
    UUID escritorioId = membroProvider.membro().getEscritorioId();
    return modeloRepository.findByEscritorioIdOrderByTituloNormalizadoAsc(escritorioId).stream()
        .map(ModeloPedidoMapper::toResponse)
        .toList();
  }

  /**
   * Cria um modelo (só administrador).
   *
   * @param request dados
   * @return o modelo criado
   * @throws ModeloPedidoException teto atingido ou título repetido (409)
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.MODELO_PEDIDO_CRIADO, recurso = RecursoAuditado.MODELO_PEDIDO)
  public ModeloPedidoResponse criar(ModeloPedidoRequest request) {
    UUID escritorioId = administrador();
    if (modeloRepository.countByEscritorioId(escritorioId) >= MAXIMO_POR_ESCRITORIO) {
      throw ModeloPedidoException.limite(MAXIMO_POR_ESCRITORIO);
    }
    exigirTituloLivre(escritorioId, request.titulo(), null);
    ModeloPedido novo = ModeloPedido.builder().escritorioId(escritorioId).build();
    ModeloPedidoMapper.aplicar(novo, request);
    return ModeloPedidoMapper.toResponse(modeloRepository.save(novo));
  }

  /**
   * Edita um modelo do escritório (só administrador).
   *
   * @param id      modelo
   * @param request dados novos (substituem todos os campos)
   * @return o modelo como ficou
   * @throws ResourceNotFoundException modelo inexistente ou de outro escritório (404)
   * @throws ModeloPedidoException     título de outro modelo do escritório (409)
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.MODELO_PEDIDO_ALTERADO, recurso = RecursoAuditado.MODELO_PEDIDO)
  public ModeloPedidoResponse atualizar(UUID id, ModeloPedidoRequest request) {
    UUID escritorioId = administrador();
    ModeloPedido modelo = doEscritorio(id, escritorioId);
    exigirTituloLivre(escritorioId, request.titulo(), id);
    ModeloPedidoMapper.aplicar(modelo, request);
    return ModeloPedidoMapper.toResponse(modeloRepository.save(modelo));
  }

  /**
   * Apaga um modelo do escritório (só administrador). Pedidos já criados com ele não mudam.
   *
   * @param id modelo
   * @throws ResourceNotFoundException modelo inexistente ou de outro escritório (404)
   */
  @Transactional
  @Auditado(acao = AcaoAuditada.MODELO_PEDIDO_APAGADO, recurso = RecursoAuditado.MODELO_PEDIDO)
  public void apagar(UUID id) {
    UUID escritorioId = administrador();
    modeloRepository.delete(doEscritorio(id, escritorioId));
  }

  private ModeloPedido doEscritorio(UUID id, UUID escritorioId) {
    return modeloRepository.findByIdAndEscritorioId(id, escritorioId)
        .orElseThrow(() -> new ResourceNotFoundException("Modelo de pedido", id));
  }

  /** Nenhum outro modelo do escritório com o mesmo título sem acento nem caixa. */
  private void exigirTituloLivre(UUID escritorioId, String titulo, UUID proprioId) {
    modeloRepository.findByEscritorioIdAndTituloNormalizado(escritorioId,
            ModeloPedido.normalizarTitulo(titulo))
        .filter(outro -> !outro.getId().equals(proprioId))
        .ifPresent(outro -> {
          throw ModeloPedidoException.duplicado();
        });
  }

  /**
   * Administrador do escritório, com o escritório sob lock: serializa as escritas do catálogo
   * (teto e título repetido), como no catálogo de obrigações.
   */
  private UUID administrador() {
    UUID escritorioId = membroProvider.exigirFuncao(FuncaoEscritorio.ADMINISTRADOR)
        .getEscritorioId();
    escritorioRepository.findByIdForUpdate(escritorioId)
        .orElseThrow(() -> new ResourceNotFoundException("Escritório", escritorioId));
    return escritorioId;
  }
}
