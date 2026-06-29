package br.com.agrocontabil.service;

import br.com.agrocontabil.config.TaxProperties;
import br.com.agrocontabil.model.Invoice;
import br.com.agrocontabil.model.InvoiceItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Serviço responsável pelo cálculo de IBS e CBS por item de invoice fiscal e agregação
 * dos totais na invoice.
 *
 * <p><strong>Regra de cálculo (LC 214/2025):</strong>
 * <ol>
 *   <li>{@code calculationBase = valorTotalItem × (1 − reductionPercentage / 100)}</li>
 *   <li>{@code ibsValue = calculationBase × ibsRate} — arredondamento HALF_UP, 2 casas</li>
 *   <li>{@code cbsValue = calculationBase × cbsRate} — arredondamento HALF_UP, 2 casas</li>
 * </ol>
 *
 * <p><strong>Contexto legal:</strong>
 * <ul>
 *   <li><strong>EC 132/2023</strong> (art. 156-A e 195, III): institui IBS e CBS.</li>
 *   <li><strong>LC 214/2025</strong> (arts. 12–18 — base de cálculo; arts. 87–92 — alíquotas):
 *       regulamenta o cálculo, incluindo reduções para regimes diferenciados.</li>
 *   <li>Em 2026 (ano-teste) os valores calculados são informativos; as alíquotas são
 *       reduzidas e parametrizadas em {@code application.yml} via {@link TaxProperties}.
 *       Nunca fixadas em código.</li>
 *   <li><strong>Incerteza documentada:</strong> o percentual de redução aplicável a cada
 *       produto agropecuário (field {@code reductionPercentage}) depende de regulamentação
 *       complementar ainda em elaboração pela RFB/CONFAZ. O field é configurável por item
 *       e será refinado em sprint futura.</li>
 * </ul>
 *
 * <p>Este serviço é <strong>puro</strong>: não acessa banco de dados. Recebe a entidade
 * {@link Invoice} (já com items carregados), calcula e escreve os campos diretamente
 * na entidade. Pode ser testado sem contexto Spring.
 */
@Service
public class IbsCbsCalculationService {

  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  private final TaxProperties tributosProperties;

  /**
   * Constrói o serviço injetando as propriedades de alíquota via {@link TaxProperties}.
   *
   * @param tributosProperties propriedades de alíquota parametrizadas no {@code application.yml}
   */
  public IbsCbsCalculationService(TaxProperties tributosProperties) {
    this.tributosProperties = tributosProperties;
  }

  /**
   * Calcula IBS e CBS para todos os items da invoice e agrega os totais na própria invoice.
   *
   * <p>As alíquotas utilizadas vêm de {@link TaxProperties} (injetadas via
   * {@code application.yml}); os valores são gravados diretamente nos campos das entidades.
   * A invoice é retornada modificada (mesma referência).
   *
   * @param invoice invoice fiscal com items já carregados; modificada in-place
   * @return a mesma {@code invoice} com os campos IBS/CBS preenchidos
   */
  public Invoice calculate(Invoice invoice) {
    BigDecimal ibsRate = tributosProperties.getIbs().getAliquota();
    BigDecimal cbsRate = tributosProperties.getCbs().getAliquota();

    BigDecimal ibsTotal = BigDecimal.ZERO;
    BigDecimal cbsTotal = BigDecimal.ZERO;

    for (InvoiceItem item : invoice.getItems()) {
      BigDecimal reduction = resolveReduction(item.getReductionPercentage());
      BigDecimal reductionFactor = BigDecimal.ONE.subtract(reduction.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
      BigDecimal calculationBase = item.getTotalValue()
          .multiply(reductionFactor)
          .setScale(2, RoundingMode.HALF_UP);

      BigDecimal ibsValue = calculationBase.multiply(ibsRate).setScale(2, RoundingMode.HALF_UP);
      BigDecimal cbsValue = calculationBase.multiply(cbsRate).setScale(2, RoundingMode.HALF_UP);

      item.setIbsCbsCalculationBase(calculationBase);
      item.setIbsRate(ibsRate);
      item.setIbsValue(ibsValue);
      item.setCbsRate(cbsRate);
      item.setCbsValue(cbsValue);

      ibsTotal = ibsTotal.add(ibsValue);
      cbsTotal = cbsTotal.add(cbsValue);
    }

    invoice.setTotalIbsValue(ibsTotal);
    invoice.setTotalCbsValue(cbsTotal);

    return invoice;
  }

  /**
   * Resolve o percentual de redução: retorna {@link BigDecimal#ZERO} quando o valor é nulo.
   *
   * @param reductionPercentage percentual de redução informado no item (pode ser nulo)
   * @return percentual efetivo, nunca nulo
   */
  private BigDecimal resolveReduction(BigDecimal reductionPercentage) {
    return reductionPercentage != null ? reductionPercentage : BigDecimal.ZERO;
  }
}
