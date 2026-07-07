package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.mapper.GuiaFiscalMapper;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monta o calendário fiscal do produtor (issue #74 — {@code GET
 * /api/calendario-fiscal/{produtorId}/vencimentos}).
 *
 * <p>Fecha a lacuna que já quebrava a tela de Calendário do Angular: o {@code CalendarioApi} do
 * frontend chama este endpoint sem fallback mock (erro direto se 404). Nesta entrega a única
 * fonte de vencimento é a {@link br.com.agrocontabil.model.GuiaFiscal} do produtor — reúsa
 * {@link GuiaFiscalRepository}, sem nova tabela. Outras fontes de prazo fiscal (obrigações,
 * declarações) ficam para quando o backend as modelar.
 *
 * <p><strong>Anti-IDOR:</strong> diferente do {@code GuiaFiscalController} (ação do Contador, sem
 * autenticação própria ainda), o calendário é uma tela do <strong>produtor</strong> — mesmo grupo
 * do Dashboard e da Apuração, onde o produtor já tem autenticação mockada real
 * ({@link AuthenticatedProdutorProvider}). O {@code produtorId} do path precisa bater com o
 * produtor autenticado; caso contrário, 404 (nunca 403 — não revela existência), mesmo padrão
 * usado em toda a API.
 */
@Service
@Transactional(readOnly = true)
public class CalendarioFiscalService {

  private final GuiaFiscalRepository guiaFiscalRepository;
  private final AuthenticatedProdutorProvider produtorProvider;

  public CalendarioFiscalService(
      GuiaFiscalRepository guiaFiscalRepository, AuthenticatedProdutorProvider produtorProvider) {
    this.guiaFiscalRepository = guiaFiscalRepository;
    this.produtorProvider = produtorProvider;
  }

  /**
   * Retorna os vencimentos fiscais do produtor, mapeados a partir das guias fiscais.
   *
   * @param produtorId identificador do produtor; deve bater com o produtor autenticado
   * @return vencimentos do produtor (vazia se nenhuma guia)
   * @throws ResourceNotFoundException se o produtorId não bater com o produtor autenticado
   *     (anti-IDOR — 404, nunca 403)
   */
  public List<VencimentoFiscalResponse> listarVencimentos(UUID produtorId) {
    UUID autenticadoId = produtorProvider.get().getId();
    if (!autenticadoId.equals(produtorId)) {
      throw new ResourceNotFoundException("Produtor", produtorId);
    }

    return guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtorId).stream()
        .map(GuiaFiscalMapper::toVencimento)
        .toList();
  }
}
