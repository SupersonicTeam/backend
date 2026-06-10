package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.exception.NotaFiscalNaoEditavelException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servico de regras de negocio para {@link NotaFiscal}.
 *
 * <p>Todas as operacoes verificam o produtor autenticado via
 * {@link AuthenticatedProdutorProvider}, garantindo que um produtor nunca acesse
 * ou modifique notas de outro produtor (anti-IDOR).
 *
 * <p>Regras de negocio:
 * <ul>
 *   <li>Criacao: status inicial RASCUNHO; totais calculados no mapper.</li>
 *   <li>Listagem: somente notas do produtor autenticado, paginada.</li>
 *   <li>Busca por ID: 404 se inexistente OU se pertencer a outro produtor.</li>
 *   <li>Atualizacao: somente se status == RASCUNHO; caso contrario, 409 Conflict.</li>
 *   <li>Exclusao: somente se status == RASCUNHO; caso contrario, 409 Conflict.</li>
 * </ul>
 */
@Service
@Transactional
public class NotaFiscalService {

  private static final Logger log = LoggerFactory.getLogger(NotaFiscalService.class);

  private final NotaFiscalRepository notaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final CalculoIbsCbsService calculoIbsCbsService;

  public NotaFiscalService(
      NotaFiscalRepository notaFiscalRepository,
      AuthenticatedProdutorProvider produtorProvider,
      CalculoIbsCbsService calculoIbsCbsService) {
    this.notaFiscalRepository = notaFiscalRepository;
    this.produtorProvider = produtorProvider;
    this.calculoIbsCbsService = calculoIbsCbsService;
  }

  /**
   * Cria uma nova nota fiscal vinculada ao produtor autenticado.
   *
   * @param request dados validados da nota fiscal
   * @return DTO de resposta com ID gerado e totais calculados
   */
  public NotaFiscalResponse create(NotaFiscalRequest request) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = NotaFiscalMapper.toEntity(request, produtor);
    // Calcula IBS/CBS após montagem dos itens e cálculo dos totais de produtos.
    calculoIbsCbsService.calcular(nota);
    NotaFiscal saved = notaFiscalRepository.save(nota);
    log.info("NotaFiscal criada: id={} produtor={} numero={}",
        saved.getId(), produtor.getId(), saved.getNumero());
    return NotaFiscalMapper.toResponse(saved);
  }

  /**
   * Lista todas as notas fiscais do produtor autenticado, paginadas.
   *
   * @param pageable parametros de paginacao e ordenacao
   * @return pagina de notas do produtor autenticado
   */
  @Transactional(readOnly = true)
  public Page<NotaFiscalResponse> findAll(Pageable pageable) {
    Produtor produtor = produtorProvider.get();
    return notaFiscalRepository
        .findByProdutorId(produtor.getId(), pageable)
        .map(NotaFiscalMapper::toResponse);
  }

  /**
   * Busca uma nota fiscal por ID, somente se pertencer ao produtor autenticado.
   *
   * <p>Retorna 404 tanto se a nota nao existir quanto se pertencer a outro produtor
   * (anti-IDOR: nunca vazar 403 com informacoes de existencia do recurso).
   *
   * @param id identificador da nota fiscal
   * @return DTO de resposta
   * @throws ResourceNotFoundException se nao encontrada ou se pertencer a outro produtor
   */
  @Transactional(readOnly = true)
  public NotaFiscalResponse findById(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));
    return NotaFiscalMapper.toResponse(nota);
  }

  /**
   * Atualiza uma nota fiscal existente, somente se estiver em status RASCUNHO.
   *
   * <p>Os itens antigos sao substituidos pelos novos (orphanRemoval cuida da exclusao
   * dos itens removidos). Os totais sao recalculados.
   *
   * @param id      identificador da nota fiscal
   * @param request novos dados validados
   * @return DTO de resposta atualizado
   * @throws ResourceNotFoundException        se nao encontrada ou pertencer a outro produtor
   * @throws NotaFiscalNaoEditavelException   se o status nao for RASCUNHO (409 Conflict)
   */
  public NotaFiscalResponse update(UUID id, NotaFiscalRequest request) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (nota.getStatus() != StatusNotaFiscal.RASCUNHO) {
      throw new NotaFiscalNaoEditavelException(id, nota.getStatus());
    }

    nota.setNumero(request.numero());
    nota.setSerie(request.serie() != null ? request.serie() : 1);
    nota.setNaturezaOperacao(request.naturezaOperacao());
    nota.setDataEmissao(request.dataEmissao());
    nota.setDataSaida(request.dataSaida());
    nota.setDestinatarioNome(request.destinatarioNome());
    nota.setDestinatarioCpfCnpj(request.destinatarioCpfCnpj());
    nota.setDestinatarioIe(request.destinatarioIe());

    if (request.destinatarioEndereco() != null) {
      nota.setDestinatarioEndereco(buildEndereco(request.destinatarioEndereco()));
    } else {
      nota.setDestinatarioEndereco(null);
    }

    // Substitui itens — orphanRemoval remove os anteriores automaticamente
    List<NotaFiscalItem> novosItens = NotaFiscalMapper.toItemEntityList(request.itens());

    nota.getItens().clear();
    novosItens.forEach(nota::addItem);

    BigDecimal totalProdutos = nota.getItens().stream()
        .map(NotaFiscalItem::getValorTotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    nota.setValorTotalProdutos(totalProdutos);
    nota.setValorTotalNota(totalProdutos);

    // Recalcula IBS/CBS após substituição dos itens e recálculo dos totais de produtos.
    calculoIbsCbsService.calcular(nota);

    NotaFiscal saved = notaFiscalRepository.save(nota);
    log.info("NotaFiscal atualizada: id={} produtor={}", saved.getId(), produtor.getId());
    return NotaFiscalMapper.toResponse(saved);
  }

  /**
   * Exclui uma nota fiscal, somente se estiver em status RASCUNHO.
   *
   * @param id identificador da nota fiscal
   * @throws ResourceNotFoundException      se nao encontrada ou pertencer a outro produtor
   * @throws NotaFiscalNaoEditavelException se o status nao for RASCUNHO (409 Conflict)
   */
  public void delete(UUID id) {
    Produtor produtor = produtorProvider.get();
    NotaFiscal nota = notaFiscalRepository
        .findByIdAndProdutorId(id, produtor.getId())
        .orElseThrow(() -> new ResourceNotFoundException("NotaFiscal", id));

    if (nota.getStatus() != StatusNotaFiscal.RASCUNHO) {
      throw new NotaFiscalNaoEditavelException(id, nota.getStatus());
    }

    notaFiscalRepository.delete(nota);
    log.info("NotaFiscal excluida: id={} produtor={}", id, produtor.getId());
  }

  // -------------------------------------------------------------------------
  // Helper privado de endereco (evita duplicar logica inline no update)
  // -------------------------------------------------------------------------

  /**
   * Converte o DTO de endereco do destinatario em um embedded {@code EnderecoDestinatario}.
   *
   * <p>Centraliza a construcao do endereco para evitar duplicacao de logica entre
   * {@link #update(UUID, NotaFiscalRequest)} e quaisquer futuros metodos que precisem
   * instanciar o endereco a partir do request.
   *
   * @param req DTO de endereco validado pelo Bean Validation
   * @return entidade embutida pronta para ser associada a uma {@code NotaFiscal}
   */
  private br.com.agrocontabil.model.EnderecoDestinatario buildEndereco(
      NotaFiscalRequest.EnderecoDestinatarioRequest req) {
    return br.com.agrocontabil.model.EnderecoDestinatario.builder()
        .logradouro(req.logradouro())
        .numero(req.numero())
        .municipio(req.municipio())
        .codigoMunicipioIbge(req.codigoMunicipioIbge())
        .uf(req.uf())
        .cep(req.cep())
        .build();
  }
}
