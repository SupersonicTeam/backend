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
 * <p><strong>Ownership em todas as operações (issue #94).</strong> O {@code produtorId} que chega
 * no payload ou na query string é apenas uma <em>afirmação do cliente</em>: antes de qualquer
 * leitura ou escrita ele precisa bater com o produtor autenticado
 * ({@link AuthenticatedProdutorProvider}). Divergiu, responde <strong>404</strong> — nunca 403 —
 * exatamente como o resto da API, para não revelar a existência do recurso alheio. Até a issue #94
 * a criação e a listagem só verificavam existência, de modo que qualquer cliente lia e gravava
 * guias de qualquer produtor informando o id.
 *
 * <p><strong>Ainda não há papel de Contador.</strong> A entidade {@code Contador}, o vínculo
 * Contador↔Produtor e o RBAC são a issue #93 (Sprint 8). Enquanto isso, estes endpoints são
 * efetivamente do próprio produtor: quem escreve a guia é quem a possui.
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
   * @throws ResourceNotFoundException se o produtorId não for o do produtor autenticado ou não
   *     existir — 404 nos dois casos, para não distinguir um do outro (anti-IDOR)
   */
  public GuiaFiscalResponse criar(GuiaFiscalRequest request) {
    exigirProdutorAutenticado(request.produtorId());

    Produtor produtor = produtorRepository.findById(request.produtorId())
        .orElseThrow(() -> new ResourceNotFoundException("Produtor", request.produtorId()));

    GuiaFiscal guia = GuiaFiscalMapper.toEntity(request, produtor);
    GuiaFiscal salva = guiaFiscalRepository.save(guia);
    return GuiaFiscalMapper.toResponse(salva);
  }

  /**
   * Lista as guias fiscais do produtor autenticado, mais recentes primeiro.
   *
   * @param produtorId identificador do produtor; precisa ser o do produtor autenticado
   * @return guias fiscais do produtor (vazia se ele não tiver nenhuma)
   * @throws ResourceNotFoundException se o produtorId não for o do produtor autenticado
   *     (anti-IDOR — 404, nunca 403)
   */
  @Transactional(readOnly = true)
  public List<GuiaFiscalResponse> listar(UUID produtorId) {
    exigirProdutorAutenticado(produtorId);

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
  /**
   * Garante que o {@code produtorId} afirmado pelo cliente é o do produtor autenticado.
   *
   * <p>Mesmo guard do {@code CalendarioFiscalService}: 404 (nunca 403) quando divergir, para não
   * revelar que aquele produtor existe.
   *
   * @param produtorId identificador informado na requisição
   * @throws ResourceNotFoundException se não for o produtor autenticado
   */
  private void exigirProdutorAutenticado(UUID produtorId) {
    if (!produtorProvider.get().getId().equals(produtorId)) {
      throw new ResourceNotFoundException("Produtor", produtorId);
    }
  }

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
