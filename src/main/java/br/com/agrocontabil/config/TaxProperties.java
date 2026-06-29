package br.com.agrocontabil.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades de alíquotas de IBS e CBS, parametrizadas via {@code application.yml}.
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
 * em {@link TaxConfig}.
 */
@ConfigurationProperties(prefix = "agrocontabil.tributos")
public class TaxProperties {

  private Ibs ibs = new Ibs();
  private Cbs cbs = new Cbs();

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

  /**
   * Propriedades do IBS (Imposto sobre Bens e Serviços — estadual/municipal).
   *
   * <p>EC 132/2023, art. 156-A; LC 214/2025.
   */
  public static class Ibs {

    /**
     * Alíquota do IBS como proporção decimal (ex.: {@code 0.0010} = 0,10 %).
     *
     * <p>Valor de referência para o ano-teste de 2026 conforme LC 214/2025.
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
     * <p>Valor de referência para o ano-teste de 2026 conforme LC 214/2025.
     * Ajustável em {@code application.yml} sem recompilar.
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
}
