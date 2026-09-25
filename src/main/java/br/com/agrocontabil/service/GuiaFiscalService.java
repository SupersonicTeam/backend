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
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

  /**
   * Tamanho padrão da página da listagem de guias (issue #103). Com cerca de 12 guias por ano,
   * cobre anos de histórico: o Angular, que ainda não pagina, continua vendo tudo na prática.
   */
  public static final int TAMANHO_PAGINA_PADRAO = 100;

  /** Tamanho máximo da página da listagem de guias (issue #103). */
  public static final int TAMANHO_PAGINA_MAXIMO = 200;

  /**
   * Maior página aceita (issue #103). Sem teto, {@code pagina × tamanho} passa de
   * {@code Integer.MAX_VALUE} e o Spring Data falha ao converter o offset para o
   * {@code setFirstResult(int)} do JPA — erro de entrada do cliente virando 500. Dez mil páginas
   * de 200 são dois milhões de guias, muito além de qualquer produtor.
   */
  public static final int PAGINA_MAXIMA = 10_000;

  /**
   * Ordem fixa da listagem: vencimento mais recente primeiro, com desempate por ID para que uma
   * guia não pule nem se repita entre páginas quando duas vencem no mesmo dia.
   */
  private static final Sort ORDEM_LISTAGEM =
      Sort.by(Sort.Direction.DESC, "dataVencimento").and(Sort.by(Sort.Direction.DESC, "id"));

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
   * Lista uma página das guias fiscais do produtor autenticado, mais recentes primeiro
   * (issue #103 — antes devolvia todas, sem limite).
   *
   * <p>Os limites de {@code pagina}/{@code tamanho} são validados no controller (Bean Validation);
   * {@link #TAMANHO_PAGINA_MAXIMO} impede que um cliente peça o histórico inteiro de uma vez.
   *
   * @param produtorId identificador do produtor; precisa ser o do produtor autenticado
   * @param pagina     página, a partir de 0
   * @param tamanho    itens por página, de 1 a {@link #TAMANHO_PAGINA_MAXIMO}
   * @return página de guias do produtor, com o total (vazia se ele não tiver nenhuma)
   * @throws ResourceNotFoundException se o produtorId não for o do produtor autenticado
   *     (anti-IDOR — 404, nunca 403)
   */
  @Transactional(readOnly = true)
  public Page<GuiaFiscalResponse> listar(UUID produtorId, int pagina, int tamanho) {
    exigirProdutorAutenticado(produtorId);

    return guiaFiscalRepository
        .findByProdutorId(produtorId, PageRequest.of(pagina, tamanho, ORDEM_LISTAGEM))
        .map(GuiaFiscalMapper::toResponse);
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
