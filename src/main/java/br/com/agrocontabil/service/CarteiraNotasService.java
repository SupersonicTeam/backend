package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.FiltroNotasCarteiraRequest;
import br.com.agrocontabil.dto.FiltroNotasRequest;
import br.com.agrocontabil.dto.NotaCarteiraResponse;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.dto.ResumoNotasCarteiraResponse;
import br.com.agrocontabil.mapper.NotaFiscalMapper;
import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalCarteiraRepository;
import br.com.agrocontabil.repository.NotaFiscalEspecificacoes;
import br.com.agrocontabil.security.MembroEscritorioProvider;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura das notas de um cliente pelo escritório (issue #177), em
 * {@code /api/contador/produtores/{produtorId}/notas}, e da carteira inteira (issue #341), em
 * {@code /api/contador/notas}. Só leitura: a revisão (aprovar, devolver) é do
 * {@code RevisaoNotaService} (#178).
 *
 * <p>A autorização vem primeiro e sempre pelo {@link ProdutorEmContextoResolver} (contador com
 * vínculo ativo; 404 fora da carteira); só depois a consulta, já filtrada pelo produtor do path.
 */
@Service
public class CarteiraNotasService {

  private final ProdutorEmContextoResolver produtorEmContexto;
  private final NotaFiscalService notaFiscalService;
  private final NotaFiscalValidacaoService validacaoService;
  private final MembroEscritorioProvider membroProvider;
  private final NotaFiscalCarteiraRepository carteiraRepository;

  public CarteiraNotasService(
      ProdutorEmContextoResolver produtorEmContexto,
      NotaFiscalService notaFiscalService,
      NotaFiscalValidacaoService validacaoService,
      MembroEscritorioProvider membroProvider,
      NotaFiscalCarteiraRepository carteiraRepository) {
    this.produtorEmContexto = produtorEmContexto;
    this.notaFiscalService = notaFiscalService;
    this.validacaoService = validacaoService;
    this.membroProvider = membroProvider;
    this.carteiraRepository = carteiraRepository;
  }

  /**
   * Notas de <strong>toda a carteira ativa</strong> do escritório, só leitura (issue #341), com os
   * filtros de {@code GET /api/notas} mais {@code produtorId} e {@code responsavel}.
   *
   * <p>Quem pede é qualquer membro do escritório (produtor 403, contador sem escritório 403
   * {@code SEM_ESCRITORIO}, ambos pelo {@link MembroEscritorioProvider}). O escopo é uma
   * subconsulta da carteira ATIVA do escritório do membro, sempre {@code and} sobre os filtros:
   * suspenso, encerrado e outro escritório não aparecem. Sem ordenação pedida, a mais recente
   * primeiro. Duas consultas, qualquer que seja o tamanho da página.
   *
   * @param filtro   filtros opcionais
   * @param pageable página e ordenação (mesma lista permitida de {@code GET /api/notas})
   * @return página de resumos, com o cliente de cada nota
   * @throws br.com.agrocontabil.exception.FiltroNotasInvalidoException ordenação ou faixa de
   *     emissão inválidas (400)
   * @throws br.com.agrocontabil.exception.FiltroCarteiraInvalidoException {@code responsavel}
   *     inválido (400)
   * @throws br.com.agrocontabil.exception.ResourceNotFoundException {@code produtorId} fora da
   *     carteira ativa (404)
   */
  @Transactional(readOnly = true)
  public Page<NotaCarteiraResponse> listarDaCarteira(FiltroNotasCarteiraRequest filtro,
      Pageable pageable) {
    // A ordenacao e conferida antes da identidade: o 400 nao depende de quem pede.
    Pageable permitida = NotaFiscalService.paginaComOrdenacaoPermitida(pageable);
    Pageable pagina = permitida.getSort().isUnsorted()
        ? PageRequest.of(permitida.getPageNumber(), permitida.getPageSize(),
            Sort.by(Sort.Order.desc("dataEmissao"), Sort.Order.asc("id")))
        : permitida;
    Specification<NotaFiscal> especificacao = escopo(filtro)
        .and(NotaFiscalService.filtros(filtro.comoFiltroDeNotas()));
    return carteiraRepository.listar(especificacao, pagina)
        .map(NotaFiscalMapper::toCarteiraResponse);
  }

  /**
   * Quantas notas da carteira há em cada caixa de triagem ({@link GrupoNotasCarteira}), com os
   * mesmos filtros da lista (menos o {@code status}, que a contagem já separa). Uma consulta
   * {@code GROUP BY}.
   *
   * @param filtro filtros opcionais
   * @return contagem por grupo e o total
   */
  @Transactional(readOnly = true)
  public ResumoNotasCarteiraResponse resumoDaCarteira(FiltroNotasCarteiraRequest filtro) {
    Specification<NotaFiscal> especificacao = escopo(filtro)
        .and(NotaFiscalService.filtros(filtro.comoFiltroDeNotasSemStatus()));
    Map<StatusNotaFiscal, Long> porStatus = carteiraRepository.contarPorStatus(especificacao);
    return new ResumoNotasCarteiraResponse(
        soma(porStatus, GrupoNotasCarteira.REVISAO), soma(porStatus, GrupoNotasCarteira.TRAVADAS),
        soma(porStatus, GrupoNotasCarteira.RASCUNHOS),
        soma(porStatus, GrupoNotasCarteira.DEVOLVIDAS),
        soma(porStatus, GrupoNotasCarteira.AUTORIZADAS),
        soma(porStatus, GrupoNotasCarteira.CANCELADAS),
        porStatus.values().stream().mapToLong(Long::longValue).sum());
  }

  private static long soma(Map<StatusNotaFiscal, Long> porStatus, GrupoNotasCarteira grupo) {
    return grupo.status().stream().mapToLong(s -> porStatus.getOrDefault(s, 0L)).sum();
  }

  /**
   * Escopo da consulta: a carteira ativa do escritório de quem pede, do responsável pedido e, se
   * houver, só do cliente pedido (que tem de estar nela: 404).
   */
  private Specification<NotaFiscal> escopo(FiltroNotasCarteiraRequest filtro) {
    Contador membro = membroProvider.membro();
    CarteiraService.PedidoResponsavel responsavel =
        CarteiraService.interpretarResponsavel(filtro.responsavel(), membro);
    Specification<NotaFiscal> escopo = NotaFiscalEspecificacoes.daCarteiraAtiva(
        membro.getEscritorioId(), responsavel.id(), responsavel.semResponsavel());
    if (filtro.produtorId() != null) {
      // 404 para cliente de outro escritorio, suspenso ou encerrado: o mesmo resolvedor das rotas
      // por cliente.
      produtorEmContexto.exigirContadorComAcesso(filtro.produtorId());
      escopo = escopo.and(NotaFiscalEspecificacoes.doProdutor(filtro.produtorId()));
    }
    return escopo;
  }

  /**
   * Notas do cliente, com os filtros de {@code GET /api/notas} (#174).
   *
   * @param produtorId cliente
   * @param filtro     filtros opcionais
   * @param pageable   página e ordenação
   * @return página de notas
   */
  public Page<NotaFiscalResponse> listar(
      UUID produtorId, FiltroNotasRequest filtro, Pageable pageable) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return notaFiscalService.findAllDoProdutor(produtorId, filtro, pageable);
  }

  /**
   * Uma nota do cliente.
   *
   * @param produtorId cliente
   * @param notaId     nota
   * @return a nota (404 se for de outro produtor)
   */
  public NotaFiscalResponse buscar(UUID produtorId, UUID notaId) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return notaFiscalService.findByIdDoProdutor(produtorId, notaId);
  }

  /**
   * Preview da DANFE de uma nota do cliente ainda não emitida (revisão, issue #178).
   *
   * @param produtorId cliente
   * @param notaId     nota
   * @return PDF com a marca d'água de rascunho
   */
  public byte[] previewDanfe(UUID produtorId, UUID notaId) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return notaFiscalService.gerarPreviewDanfeDoProdutor(produtorId, notaId);
  }

  /**
   * Histórico de inconsistências de uma nota do cliente.
   *
   * @param produtorId cliente
   * @param notaId     nota
   * @return histórico (404 se a nota for de outro produtor)
   */
  public List<NotaFiscalValidacaoResponse> validacoes(UUID produtorId, UUID notaId) {
    produtorEmContexto.exigirContadorComAcesso(produtorId);
    return validacaoService.listarValidacoesDoProdutor(produtorId, notaId);
  }
}
