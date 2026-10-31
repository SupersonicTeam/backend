package br.com.agrocontabil.service;

import br.com.agrocontabil.model.NaturezaOperacaoTributaria;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço de cálculo tributário de IBS e CBS por item de nota fiscal, com classificação da
 * operação (entrada/saída) e resolução da redução de base por NCM
 * (Sprint 4 — issue Agrocontabil#30, RF015).
 *
 * <p>Evolução do antigo {@code CalculoIbsCbsService}: além de calcular IBS/CBS por item e agregar
 * os totais na nota, agora <strong>classifica cada item por operação</strong> e resolve a
 * <strong>redução por NCM</strong>.
 *
 * <p><strong>Regra de cálculo (LC 214/2025):</strong>
 * <ol>
 *   <li>{@code reducao} = {@code percentualReducao} informado no item; se nulo, a redução da
 *       tabela oficial pelo NCM ({@link ReducaoNcmResolver}: alíquota zero do Anexo I/XV = 100%,
 *       in natura e Anexos IX/VII = 60%, senão 0 — issue #272). A redução e o {@code cClassTrib}
 *       aplicados ficam no item ({@code percentualReducaoAplicado}/{@code cClassTribAplicado}).</li>
 *   <li>{@code baseCalculo = valorTotalItem} (a LC 214/2025 reduz a alíquota, não a base —
 *       issue #278)</li>
 *   <li>{@code valorIbs = baseCalculo × aliquotaIbs × (1 − reducao / 100)} — um arredondamento
 *       HALF_UP, 2 casas; idem a CBS. No XML: {@code vBC} cheio, {@code pIBSUF} cheia e
 *       {@code gRed.pAliqEfet} = alíquota × (1 − reducao / 100)</li>
 * </ol>
 *
 * <p><strong>Alíquotas (RN002, issues Agrocontabil#31 e #86):</strong> a alíquota de IBS
 * (parcelas estadual e municipal, por UF aplicável à operação) e a de CBS são resolvidas pelo
 * {@link AliquotaIbsCbsResolver} — componente <strong>único</strong> compartilhado com o cálculo
 * de crédito ({@code CreditoTributarioService}), o que garante a simetria débito × crédito por
 * construção. As parcelas reais são gravadas no item ({@code aliquotaIbsUf}/{@code aliquotaIbsMun},
 * {@code valorIbsUf}/{@code valorIbsMun}) e consumidas pelo {@code NotaFiscalNfeMapper}. A
 * validação de compatibilidade entre o âmbito do CFOP e a relação de UFs já é feita pelo
 * {@code FiscalRulesValidator} (issue #20) no pipeline de emissão — não é duplicada aqui.
 *
 * <p><strong>Classificação por operação (RF015):</strong> pelo 1.º dígito do CFOP do item —
 * {@code 5/6/7} = saída → {@link NaturezaOperacaoTributaria#DEBITO}; {@code 1/2/3} = entrada →
 * {@link NaturezaOperacaoTributaria#CREDITO}; CFOP nulo/fora do padrão → natureza nula
 * (conservador). A natureza é persistida por item e consumida pela apuração mensal (issue #41)
 * para compor débitos e créditos do período. Espelha a lógica de âmbito/tipo já modelada em
 * {@code CfopReferencia} (issue #20), mas sem exigir que o CFOP conste na tabela de referência —
 * a classificação débito/crédito depende apenas do 1.º dígito.
 *
 * <p><strong>Contexto legal:</strong>
 * <ul>
 *   <li><strong>EC 132/2023</strong> (art. 156-A e 195, III): institui IBS e CBS.</li>
 *   <li><strong>LC 214/2025</strong> (arts. 12–18 — base de cálculo; arts. 87–92 — alíquotas):
 *       regulamenta o cálculo, incluindo reduções para regimes diferenciados.</li>
 *   <li>Em 2026 (ano-teste) os valores calculados são informativos; as alíquotas são
 *       reduzidas e vêm de tabelas de referência (com fallback parametrizado em
 *       {@code application.yml}). Nunca fixadas em código.</li>
 *   <li><strong>Incerteza documentada:</strong> as tabelas oficiais de redução por NCM da
 *       LC 214/2025 ainda não foram finalizadas pela RFB/CONFAZ. Os percentuais de redução por
 *       NCM são <em>referência de ano-teste</em> (seed do changeset {@code 0012}), configuráveis
 *       sem recompilar, e servem apenas de default quando o item não informa a redução.</li>
 * </ul>
 *
 * <p>Diferentemente do serviço anterior, este consulta a tabela de referência de NCM (leitura),
 * portanto opera em transação (read-only) quando invocado dentro do fluxo de nota fiscal. Os
 * campos calculados são escritos diretamente na entidade {@link NotaFiscal} (mesma referência).
 */
@Service
public class TributacaoService {

  private static final BigDecimal CEM = new BigDecimal("100");

  private final ReducaoNcmResolver reducaoNcmResolver;
  private final AliquotaIbsCbsResolver aliquotaResolver;

  /**
   * Constrói o serviço injetando os resolvers compartilhados com o cálculo de crédito.
   *
   * @param reducaoNcmResolver resolver compartilhado da redução de base por NCM (issue #52), que
   *     aplica a precedência item → NCM → zero
   * @param aliquotaResolver resolver compartilhado das alíquotas de IBS por UF e CBS (issue #86)
   */
  public TributacaoService(
      ReducaoNcmResolver reducaoNcmResolver, AliquotaIbsCbsResolver aliquotaResolver) {
    this.reducaoNcmResolver = reducaoNcmResolver;
    this.aliquotaResolver = aliquotaResolver;
  }

  /**
   * Calcula IBS/CBS de todos os itens da nota, classifica a natureza da operação e agrega os
   * totais na própria nota.
   *
   * <p>As alíquotas vêm do {@link AliquotaIbsCbsResolver}; a redução de base combina o percentual
   * do item (precedência) com o default por NCM. Os valores e a natureza são gravados diretamente nas
   * entidades. A nota é retornada modificada (mesma referência).
   *
   * @param nota nota fiscal com itens já carregados; modificada in-place
   * @return a mesma {@code nota} com IBS/CBS, natureza e totais preenchidos
   */
  @Transactional(readOnly = true)
  public NotaFiscal calcular(NotaFiscal nota) {
    // A alíquota de IBS (estadual + municipal) é resolvida pela UF aplicável à operação —
    // a mesma para todos os itens da nota. A CBS (federal) vem do mesmo resolvedor.
    AliquotasVigentes vigentes = aliquotaResolver.resolver(nota);
    BigDecimal aliquotaCbs = vigentes.cbs();

    BigDecimal totalIbs = BigDecimal.ZERO;
    BigDecimal totalCbs = BigDecimal.ZERO;

    for (NotaFiscalItem item : nota.getItens()) {
      ReducaoNcmResolver.ReducaoAplicada aplicada = reducaoNcmResolver.tratamento(item);
      BigDecimal reducao = aplicada.percentualReducao();
      BigDecimal fatorReducao = BigDecimal.ONE.subtract(reducao.divide(CEM, 10, RoundingMode.HALF_UP));
      // A LC 214/2025 reduz a ALIQUOTA, nao a base: no XML, vBC e o valor cheio e o tributo e
      // vBC x pAliqEfet (grupo gRed, issue #278). Um arredondamento so, sobre a conta inteira.
      BigDecimal baseCalculo = item.getValorTotal().setScale(2, RoundingMode.HALF_UP);
      BigDecimal baseEfetiva = baseCalculo.multiply(fatorReducao);

      BigDecimal valorIbs = baseEfetiva.multiply(vigentes.ibsTotal())
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal valorCbs = baseEfetiva.multiply(aliquotaCbs).setScale(2, RoundingMode.HALF_UP);

      // Parcela estadual pelo valor exato; a municipal fecha o total sem perda no arredondamento.
      BigDecimal valorIbsUf =
          baseEfetiva.multiply(vigentes.ibsEstadual()).setScale(2, RoundingMode.HALF_UP);
      BigDecimal valorIbsMun = valorIbs.subtract(valorIbsUf);

      item.setPercentualReducaoAplicado(reducao);
      item.setCClassTribAplicado(aplicada.cClassTrib());
      item.setBaseCalculoIbsCbs(baseCalculo);
      item.setAliquotaIbs(vigentes.ibsTotal());
      item.setAliquotaIbsUf(vigentes.ibsEstadual());
      item.setAliquotaIbsMun(vigentes.ibsMunicipal());
      item.setValorIbs(valorIbs);
      item.setValorIbsUf(valorIbsUf);
      item.setValorIbsMun(valorIbsMun);
      item.setAliquotaCbs(aliquotaCbs);
      item.setValorCbs(valorCbs);
      item.setNaturezaOperacao(classificarNatureza(item.getCfop()));

      totalIbs = totalIbs.add(valorIbs);
      totalCbs = totalCbs.add(valorCbs);
    }

    nota.setValorTotalIbs(totalIbs);
    nota.setValorTotalCbs(totalCbs);

    return nota;
  }

  /**
   * Classifica a natureza tributária pelo 1.º dígito do CFOP: {@code 5/6/7} → DEBITO (saída);
   * {@code 1/2/3} → CREDITO (entrada); nulo/fora do padrão → {@code null} (conservador).
   *
   * @param cfop CFOP do item (pode ser nulo)
   * @return natureza tributária, ou {@code null} quando o CFOP não permite classificar
   */
  private NaturezaOperacaoTributaria classificarNatureza(String cfop) {
    if (cfop == null || cfop.isBlank()) {
      return null;
    }
    char digito = cfop.trim().charAt(0);
    return switch (digito) {
      case '1', '2', '3' -> NaturezaOperacaoTributaria.CREDITO;
      case '5', '6', '7' -> NaturezaOperacaoTributaria.DEBITO;
      default -> null;
    };
  }
}
