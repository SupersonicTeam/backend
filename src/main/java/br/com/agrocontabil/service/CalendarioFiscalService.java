package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
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
 * <p><strong>Sem ownership por autenticação:</strong> assim como {@code GuiaFiscalService}, este
 * endpoint recebe o {@code produtorId} explícito na URL — não há Contador autenticado ainda para
 * restringir o acesso, e o produtor consultado pode não ser o mesmo do
 * {@code MockProdutorFilter} (o calendário é consultado pelo próprio produtor no seu painel).
 */
@Service
@Transactional(readOnly = true)
public class CalendarioFiscalService {

  private final GuiaFiscalRepository guiaFiscalRepository;

  public CalendarioFiscalService(GuiaFiscalRepository guiaFiscalRepository) {
    this.guiaFiscalRepository = guiaFiscalRepository;
  }

  /**
   * Retorna os vencimentos fiscais do produtor, mapeados a partir das guias fiscais.
   *
   * @param produtorId identificador do produtor
   * @return vencimentos do produtor (vazia se nenhuma guia ou produtor inexistente)
   */
  public List<VencimentoFiscalResponse> listarVencimentos(UUID produtorId) {
    return guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtorId).stream()
        .map(GuiaFiscalMapper::toVencimento)
        .toList();
  }
}
