package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.FiltroNotasRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Leitura das notas de um cliente pelo escritório (issue #177), em
 * {@code /api/contador/produtores/{produtorId}/notas}. Só leitura: a revisão (aprovar, devolver)
 * chega com a issue #178.
 *
 * <p>A autorização vem primeiro e sempre pelo {@link ProdutorEmContextoResolver} (contador com
 * vínculo ativo; 404 fora da carteira); só depois a consulta, já filtrada pelo produtor do path.
 */
@Service
public class CarteiraNotasService {

  private final ProdutorEmContextoResolver produtorEmContexto;
  private final NotaFiscalService notaFiscalService;
  private final NotaFiscalValidacaoService validacaoService;

  public CarteiraNotasService(
      ProdutorEmContextoResolver produtorEmContexto,
      NotaFiscalService notaFiscalService,
      NotaFiscalValidacaoService validacaoService) {
    this.produtorEmContexto = produtorEmContexto;
    this.notaFiscalService = notaFiscalService;
    this.validacaoService = validacaoService;
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
