package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do JWT de acesso (issue #206), em {@code app.seguranca.jwt}.
 *
 * <p>A validação do segredo (presença, tamanho, valor de exemplo) fica no {@code JwtService}, que é
 * quem o usa: a aplicação não sobe com um segredo que permitiria forjar tokens.
 *
 * @param segredo                segredo HMAC, de {@code JWT_SECRET}; no mínimo 32 bytes
 * @param emissor                claim {@code iss} emitida e exigida
 * @param audiencia              claim {@code aud} emitida e exigida
 * @param validade               vida do token de acesso
 * @param toleranciaRelogio      folga na checagem de {@code exp}/{@code nbf} entre servidores
 * @param permitirSegredoEfemero sem segredo configurado, gerar um aleatório na subida (tokens morrem
 *                               a cada reinício). Só {@code dev} e {@code test} ligam
 */
@ConfigurationProperties(prefix = "app.seguranca.jwt")
public record JwtProperties(
    String segredo,
    @DefaultValue("agrocontabil-backend") String emissor,
    @DefaultValue("agrocontabil-api") String audiencia,
    @DefaultValue("PT15M") Duration validade,
    @DefaultValue("PT30S") Duration toleranciaRelogio,
    @DefaultValue("false") boolean permitirSegredoEfemero) {

  /** Sem o segredo: o {@code toString} padrão do record o poria em log de configuração. */
  @Override
  public String toString() {
    return "JwtProperties[emissor=" + emissor + ", audiencia=" + audiencia + ", validade="
        + validade + "]";
  }
}
