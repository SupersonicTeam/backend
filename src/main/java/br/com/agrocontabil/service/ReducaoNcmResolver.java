package br.com.agrocontabil.service;

import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Resolve o percentual de redução da base de IBS/CBS aplicável a um item de nota fiscal
 * (Sprint 4 — issue Agrocontabil#30; correção de simetria débito × crédito — issue Agrocontabil#52).
 *
 * <p>Componente <strong>único</strong> que centraliza a regra de precedência da redução, consumido
 * tanto pelo cálculo de <em>débito</em> ({@link TributacaoService}) quanto pelo de <em>crédito</em>
 * ({@link CreditoTributarioService}). Antes da issue #52 a lógica estava duplicada nos dois serviços
 * e divergiu: o crédito tratava {@code percentualReducao == null} como redução zero, ignorando o
 * default por NCM que o débito aplicava — superestimando o crédito e quebrando a simetria que o
 * Javadoc afirmava existir. Extrair a regra para um só ponto elimina a fonte da divergência.
 *
 * <p><strong>Precedência (LC 214/2025):</strong>
 * <ol>
 *   <li>se o item informa {@code percentualReducao} (não nulo), esse valor prevalece;</li>
 *   <li>senão, usa a redução padrão do NCM (tabela {@code ncm_agropecuario});</li>
 *   <li>se o NCM não for de referência ou não tiver redução, retorna {@link BigDecimal#ZERO}
 *       (sem redução — postura conservadora).</li>
 * </ol>
 *
 * <p><strong>Incerteza documentada:</strong> as tabelas oficiais de redução por NCM da LC 214/2025
 * ainda não foram finalizadas pela RFB/CONFAZ. Os percentuais são referência de ano-teste (seed do
 * changeset {@code 0012}), configuráveis sem recompilar, e servem apenas de default quando o item
 * não informa a redução.
 *
 * <p>Este componente consulta a tabela de referência de NCM (leitura), portanto deve ser chamado
 * dentro de uma transação (read-only) pelo serviço que o utiliza.
 */
@Component
public class ReducaoNcmResolver {

  private final NcmReferenciaRepository ncmReferenciaRepository;

  /**
   * Constrói o resolver injetando o repositório read-only da tabela {@code ncm_agropecuario}.
   *
   * @param ncmReferenciaRepository repositório usado para resolver a redução padrão por NCM
   */
  public ReducaoNcmResolver(NcmReferenciaRepository ncmReferenciaRepository) {
    this.ncmReferenciaRepository = ncmReferenciaRepository;
  }

  /**
   * Resolve o percentual de redução efetivo do item aplicando a precedência item → NCM → zero.
   *
   * @param item item da nota fiscal
   * @return percentual de redução efetivo (0–100), nunca nulo
   */
  public BigDecimal resolver(NotaFiscalItem item) {
    if (item.getPercentualReducao() != null) {
      return item.getPercentualReducao();
    }
    String ncm = item.getNcm();
    if (ncm == null || ncm.isBlank()) {
      return BigDecimal.ZERO;
    }
    return ncmReferenciaRepository
        .findPercentualReducaoPadraoByCodigo(ncm)
        .orElse(BigDecimal.ZERO);
  }
}
