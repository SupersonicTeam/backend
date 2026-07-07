package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.GuiaFiscalRequest;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
import br.com.agrocontabil.exception.GuiaJaPagaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.GuiaFiscalMapper;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de negócio das guias fiscais anexadas pelo Contador (issue #74).
 *
 * <p><strong>Sem autenticação de Contador:</strong> a criação e a listagem recebem o
 * {@code produtorId} explícito (não há entidade/login de Contador no backend ainda — débito
 * técnico para a Sprint 8, RBAC produtor/contador). Já {@link #marcarComoPaga} usa o produtor
 * autenticado real ({@link AuthenticatedProdutorProvider}) com o padrão anti-IDOR já
 * consolidado no restante da API: {@code findByIdAndProdutorId} + 404 para guia de outro
 * produtor.
 */
@Service
@Transactional
public class GuiaFiscalService {

  private final GuiaFiscalRepository guiaFiscalRepository;
  private final ProdutorRepository produtorRepository;
  private final AuthenticatedProdutorProvider produtorProvider;
  private final Clock clock;

  public GuiaFiscalService(
      GuiaFiscalRepository guiaFiscalRepository,
      ProdutorRepository produtorRepository,
      AuthenticatedProdutorProvider produtorProvider,
      Clock clock) {
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.produtorRepository = produtorRepository;
    this.produtorProvider = produtorProvider;
    this.clock = clock;
  }

  /**
   * Cria uma guia fiscal para o produtor informado no request.
   *
   * @param request dados da guia, incluindo o {@code produtorId} destinatário
   * @return a guia criada, com status PENDENTE
   * @throws ResourceNotFoundException se o produtorId informado não existir
   */
  public GuiaFiscalResponse criar(GuiaFiscalRequest request) {
    Produtor produtor = produtorRepository.findById(request.produtorId())
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", request.produtorId()));

    GuiaFiscal guia = GuiaFiscalMapper.toEntity(request, produtor);
    GuiaFiscal salva = guiaFiscalRepository.save(guia);
    return GuiaFiscalMapper.toResponse(salva);
  }

  /**
   * Lista as guias fiscais de um produtor, mais recentes primeiro.
   *
   * <p>Não valida a existência do produtor: um {@code produtorId} inexistente simplesmente
   * retorna uma lista vazia (não há ownership a proteger aqui — sem autenticação de Contador).
   *
   * @param produtorId identificador do produtor
   * @return guias fiscais do produtor (vazia se nenhuma ou produtor inexistente)
   */
  @Transactional(readOnly = true)
  public List<GuiaFiscalResponse> listar(UUID produtorId) {
    return guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtorId).stream()
        .map(GuiaFiscalMapper::toResponse)
        .toList();
  }

  /**
   * Marca como paga a guia fiscal indicada, para o produtor autenticado.
   *
   * @param id identificador da guia fiscal
   * @return a guia atualizada, com status PAGA e dataPagamento preenchida
   * @throws ResourceNotFoundException se a guia não existir ou não pertencer ao produtor
   *     autenticado (anti-IDOR — 404, nunca 403)
   * @throws GuiaJaPagaException se a guia já estiver PAGA
   */
  public GuiaFiscalResponse marcarComoPaga(UUID id) {
    UUID produtorId = produtorProvider.get().getId();
    GuiaFiscal guia = guiaFiscalRepository.findByIdAndProdutorId(id, produtorId)
        .orElseThrow(() -> new ResourceNotFoundException("Guia fiscal", id));

    if (guia.getStatus() == StatusGuiaFiscal.PAGA) {
      throw new GuiaJaPagaException(id);
    }

    guia.setStatus(StatusGuiaFiscal.PAGA);
    guia.setDataPagamento(OffsetDateTime.now(clock));
    return GuiaFiscalMapper.toResponse(guia);
  }
}
