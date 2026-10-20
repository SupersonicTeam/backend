package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limite geral de requisições da API (issue #150), em {@code app.seguranca.limite}.
 *
 * <p>Proteção técnica contra abuso, não regra de produto: a cota semanal da Ceres (#166) e o
 * bulkhead dela (#157) seguem valendo por cima.
 *
 * @param geralPorMinuto requisições em {@code /api/**} por cliente por minuto
 * @param chatPorMinuto  perguntas à Ceres ({@code /api/assistente/chat*}) por cliente por minuto
 * @param maxChaves      clientes acompanhados ao mesmo tempo (teto de memória)
 */
@ConfigurationProperties(prefix = "app.seguranca.limite")
public record LimiteTaxaProperties(
    @DefaultValue("100") int geralPorMinuto,
    @DefaultValue("20") int chatPorMinuto,
    @DefaultValue("10000") int maxChaves) {

  /** Valores que desligariam o limite em silêncio impedem a subida. */
  public LimiteTaxaProperties {
    if (geralPorMinuto < 1 || chatPorMinuto < 1 || maxChaves < 1) {
      throw new IllegalStateException("app.seguranca.limite: valores precisam ser positivos.");
    }
  }
}
