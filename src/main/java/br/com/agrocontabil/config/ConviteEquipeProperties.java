package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Convites de equipe do escritório (issue #228), em {@code app.escritorio.convite}.
 *
 * @param validade    vida do convite (padrão 7 dias)
 * @param urlFrontend página do Angular que recebe o token no fragmento ({@code #token=})
 * @param maxPorDia   teto de convites de equipe por escritório em 24 horas
 */
@ConfigurationProperties(prefix = "app.escritorio.convite")
public record ConviteEquipeProperties(
    @DefaultValue("P7D") Duration validade,
    @DefaultValue("http://localhost:4200/equipe/convite") String urlFrontend,
    @DefaultValue("30") int maxPorDia) {

  /** Valores fora da faixa impedem a subida. */
  public ConviteEquipeProperties {
    if (validade.compareTo(Duration.ofHours(1)) < 0
        || validade.compareTo(Duration.ofDays(30)) > 0) {
      throw new IllegalStateException(
          "app.escritorio.convite.validade precisa estar entre 1 hora e 30 dias.");
    }
    if (maxPorDia < 1) {
      throw new IllegalStateException("app.escritorio.convite.max-por-dia precisa ser positivo.");
    }
  }
}
