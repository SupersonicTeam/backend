package br.com.agrocontabil.service;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Serviço responsável pelo cálculo de IBS e CBS por item de nota fiscal e agregação
 * dos totais na nota.
 *
 * <p><strong>Regra de cálculo (LC 214/2025):</strong>
 * <ol>
 *   <li>{@code baseCalculo = valorTotalItem × (1 − percentualReducao / 100)}</li>
 *   <li>{@code valorIbs = baseCalculo × aliquotaIbs} — arredondamento HALF_UP, 2 casas</li>
 *   <li>{@code valorCbs = baseCalculo × aliquotaCbs} — arredondamento HALF_UP, 2 casas</li>
 * </ol>
 *
 * <p><strong>Contexto legal:</strong>
 * <ul>
 *   <li><strong>EC 132/2023</strong> (art. 156-A e 195, III): institui IBS e CBS.</li>
 *   <li><strong>LC 214/2025</strong> (arts. 12–18 — base de cálculo; arts. 87–92 — alíquotas):
 *       regulamenta o cálculo, incluindo reduções para regimes diferenciados.</li>
 *   <li>Em 2026 (ano-teste) os valores calculados são informativos; as alíquotas são
 *       reduzidas e parametrizadas em {@code application.yml} via {@link TributosProperties}.
 *       Nunca fixadas em código.</li>
 *   <li><strong>Incerteza documentada:</strong> o percentual de redução aplicável a cada
 *       produto agropecuário (campo {@code percentualReducao}) depende de regulamentação
 *       complementar ainda em elaboração pela RFB/CONFAZ. O campo é configurável por item
 *       e será refinado em sprint futura.</li>
 * </ul>
 *
 * <p>Este serviço é <strong>puro</strong>: não acessa banco de dados. Recebe a entidade
 * {@link NotaFiscal} (já com itens carregados), calcula e escreve os campos diretamente
 * na entidade. Pode ser testado sem contexto Spring.
 */
@Service
public class CalculoIbsCbsService {

  private static final BigDecimal CEM = new BigDecimal("100");

  private final TributosProperties tributosProperties;

  /**
   * Constrói o serviço injetando as propriedades de alíquota via {@link TributosProperties}.
   *
   * @param tributosProperties propriedades de alíquota parametrizadas no {@code application.yml}
   */
  public CalculoIbsCbsService(TributosProperties tributosProperties) {
    this.tributosProperties = tributosProperties;
  }

  /**
   * Calcula IBS e CBS para todos os itens da nota e agrega os totais na própria nota.
   *
   * <p>As alíquotas utilizadas vêm de {@link TributosProperties} (injetadas via
   * {@code application.yml}); os valores são gravados diretamente nos campos das entidades.
   * A nota é retornada modificada (mesma referência).
   *
   * @param nota nota fiscal com itens já carregados; modificada in-place
   * @return a mesma {@code nota} com os campos IBS/CBS preenchidos
   */
  public NotaFiscal calcular(NotaFiscal nota) {
    BigDecimal aliquotaIbs = tributosProperties.getIbs().getAliquota();
    BigDecimal aliquotaCbs = tributosProperties.getCbs().getAliquota();

    BigDecimal totalIbs = BigDecimal.ZERO;
    BigDecimal totalCbs = BigDecimal.ZERO;

    for (NotaFiscalItem item : nota.getItens()) {
      BigDecimal reducao = resolveReducao(item.getPercentualReducao());
      BigDecimal fatorReducao = BigDecimal.ONE.subtract(reducao.divide(CEM, 10, RoundingMode.HALF_UP));
      BigDecimal baseCalculo = item.getValorTotal()
          .multiply(fatorReducao)
          .setScale(2, RoundingMode.HALF_UP);

      BigDecimal valorIbs = baseCalculo.multiply(aliquotaIbs).setScale(2, RoundingMode.HALF_UP);
      BigDecimal valorCbs = baseCalculo.multiply(aliquotaCbs).setScale(2, RoundingMode.HALF_UP);

      item.setBaseCalculoIbsCbs(baseCalculo);
      item.setAliquotaIbs(aliquotaIbs);
      item.setValorIbs(valorIbs);
      item.setAliquotaCbs(aliquotaCbs);
      item.setValorCbs(valorCbs);

      totalIbs = totalIbs.add(valorIbs);
      totalCbs = totalCbs.add(valorCbs);
    }

    nota.setValorTotalIbs(totalIbs);
    nota.setValorTotalCbs(totalCbs);

    return nota;
  }

  /**
   * Resolve o percentual de redução: retorna {@link BigDecimal#ZERO} quando o valor é nulo.
   *
   * @param percentualReducao percentual de redução informado no item (pode ser nulo)
   * @return percentual efetivo, nunca nulo
   */
  private BigDecimal resolveReducao(BigDecimal percentualReducao) {
    return percentualReducao != null ? percentualReducao : BigDecimal.ZERO;
  }
}
