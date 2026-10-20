package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Auditoria (issue #149, RNF012), em {@code app.auditoria}.
 *
 * @param retencaoAnos por quantos anos o registro fica guardado; nunca menos de 5 (RNF012, e o
 *                     mesmo prazo da guarda fiscal — CTN art. 173). Abaixo disso a aplicação não
 *                     sobe: uma configuração errada apagaria auditoria que a lei manda guardar.
 */
@ConfigurationProperties(prefix = "app.auditoria")
public record AuditoriaProperties(@DefaultValue("5") int retencaoAnos) {

  /** Prazo mínimo de guarda do RNF012. */
  public static final int RETENCAO_MINIMA_ANOS = 5;

  public AuditoriaProperties {
    if (retencaoAnos < RETENCAO_MINIMA_ANOS) {
      throw new IllegalArgumentException("app.auditoria.retencao-anos deve ser pelo menos "
          + RETENCAO_MINIMA_ANOS + " (RNF012); recebido " + retencaoAnos);
    }
  }
}
