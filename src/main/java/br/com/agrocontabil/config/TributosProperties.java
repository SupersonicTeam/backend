package br.com.agrocontabil.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades de alíquotas de IBS e CBS, parametrizadas via {@code application.yml}.
 *
 * <p><strong>Papel a partir da issue Agrocontabil#86:</strong> as alíquotas aqui definidas são o
 * <em>fallback conservador</em> do {@link br.com.agrocontabil.service.AliquotaIbsCbsResolver}
 * — usadas apenas quando não há alíquota de IBS na tabela {@code aliquota_ibs_uf} para a UF
 * aplicável (e, a partir da issue #85, quando não há vigência para a data de emissão). Nenhum
 * serviço lê {@code ibs.aliquota}/{@code cbs.aliquota} diretamente; toda resolução de alíquota
 * passa pelo resolvedor. O {@code credito.percentualAproveitamento} continua sendo lido pelo
 * {@code CreditoTributarioService}.
 *
 * <p>Prefix: {@code agrocontabil.tributos}. Configurado em {@code application.yml}:
 *
 * <pre>
 * agrocontabil:
 *   tributos:
 *     ibs:
 *       aliquota: 0.0010   # 0,10 % — alíquota-teste 2026 (EC 132/2023 + LC 214/2025)
 *     cbs:
 *       aliquota: 0.0090   # 0,90 % — alíquota-teste 2026 (EC 132/2023 + LC 214/2025)
 * </pre>
 *
 * <p><strong>Contexto legal:</strong>
 * <ul>
 *   <li><strong>EC 132/2023</strong> (art. 156-A, § 5.º): alíquotas de referência do IBS e da
 *       CBS fixadas pelo Senado Federal; estados e municípios definirão suas parcelas.</li>
 *   <li><strong>LC 214/2025</strong> (arts. 87–92): período de transição 2026–2032; em 2026
 *       as alíquotas são reduzidas e os valores aparecem na NF apenas de forma informativa,
 *       sem recolhimento efetivo.</li>
 *   <li>Os valores {@code 0.0010} (IBS) e {@code 0.0090} (CBS) são referências técnicas
 *       amplamente citadas para o ano-teste de 2026 — <strong>não cravados em código</strong>,
 *       ajustáveis sem recompilar.</li>
 * </ul>
 *
 * <p>Registrada via {@link org.springframework.boot.context.properties.EnableConfigurationProperties}
 * em {@link TributosConfig}.
 */
@ConfigurationProperties(prefix = "agrocontabil.tributos")
public class TributosProperties {

  private Ibs ibs = new Ibs();
  private Cbs cbs = new Cbs();
  private Credito credito = new Credito();

  /** Retorna as propriedades do IBS. */
  public Ibs getIbs() {
    return ibs;
  }

  /** Define as propriedades do IBS. */
  public void setIbs(Ibs ibs) {
    this.ibs = ibs;
  }

  /** Retorna as propriedades da CBS. */
  public Cbs getCbs() {
    return cbs;
  }

  /** Define as propriedades da CBS. */
  public void setCbs(Cbs cbs) {
    this.cbs = cbs;
  }

  /** Retorna as propriedades do crédito tributário de entrada. */
  public Credito getCredito() {
    return credito;
  }

  /** Define as propriedades do crédito tributário de entrada. */
  public void setCredito(Credito credito) {
    this.credito = credito;
  }

  /**
   * Propriedades do IBS (Imposto sobre Bens e Serviços — estadual/municipal).
   *
   * <p>EC 132/2023, art. 156-A; LC 214/2025.
   */
  public static class Ibs {

    /**
     * Alíquota do IBS como proporção decimal (ex.: {@code 0.0010} = 0,10 %).
     *
     * <p>Valor de referência para o ano-teste de 2026 conforme LC 214/2025. Fallback quando a
     * UF não consta em {@code aliquota_ibs_uf} (dividido 50/50 entre estado e município).
     * Ajustável em {@code application.yml} sem recompilar.
     */
    private BigDecimal aliquota = new BigDecimal("0.0010");

    /** Retorna a alíquota do IBS. */
    public BigDecimal getAliquota() {
      return aliquota;
    }

    /** Define a alíquota do IBS. */
    public void setAliquota(BigDecimal aliquota) {
      this.aliquota = aliquota;
    }
  }

  /**
   * Propriedades da CBS (Contribuição sobre Bens e Serviços — federal).
   *
   * <p>EC 132/2023, art. 195, III; LC 214/2025.
   */
  public static class Cbs {

    /**
     * Alíquota da CBS como proporção decimal (ex.: {@code 0.0090} = 0,90 %).
     *
     * <p>Valor de referência para o ano-teste de 2026 conforme LC 214/2025. Fallback do
     * resolvedor de alíquota. Ajustável em {@code application.yml} sem recompilar.
     */
    private BigDecimal aliquota = new BigDecimal("0.0090");

    /** Retorna a alíquota da CBS. */
    public BigDecimal getAliquota() {
      return aliquota;
    }

    /** Define a alíquota da CBS. */
    public void setAliquota(BigDecimal aliquota) {
      this.aliquota = aliquota;
    }
  }

  /**
   * Propriedades do crédito tributário de IBS/CBS nas aquisições (notas de entrada).
   *
   * <p><strong>Base legal:</strong> a não-cumulatividade do IBS/CBS garante ao adquirente
   * crédito integral do imposto incidente na operação anterior (EC 132/2023, art. 156-A,
   * § 1.º, VIII; LC 214/2025, arts. 47 e seguintes). Para o produtor rural, a LC 214/2025
   * prevê regimes diferenciados (arts. 164 e seguintes — produtor rural não contribuinte
   * gera <em>crédito presumido</em> ao adquirente, com percentual dependente de ato do
   * Comitê Gestor/RFB ainda não editado). Por essa incerteza regulatória, o percentual de
   * aproveitamento é <strong>parametrizado</strong> — nunca cravado em código.
   */
  public static class Credito {

    /**
     * Percentual do crédito aproveitável sobre o IBS/CBS da operação de entrada, de 0 a 100
     * (ex.: {@code 100} = crédito integral, regra geral da não-cumulatividade).
     *
     * <p>Ajustável em {@code application.yml}
     * ({@code agrocontabil.tributos.credito.percentual-aproveitamento}) sem recompilar,
     * para refletir a regulamentação complementar da LC 214/2025 quando editada.
     */
    private BigDecimal percentualAproveitamento = new BigDecimal("100");

    /** Retorna o percentual de aproveitamento do crédito (0–100). */
    public BigDecimal getPercentualAproveitamento() {
      return percentualAproveitamento;
    }

    /** Define o percentual de aproveitamento do crédito (0–100). */
    public void setPercentualAproveitamento(BigDecimal percentualAproveitamento) {
      this.percentualAproveitamento = percentualAproveitamento;
    }
  }
}
