package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Convites da carteira do escritório (issue #177), em {@code app.carteira.convite}.
 *
 * @param validade    vida do convite (padrão 14 dias)
 * @param urlFrontend página do Angular onde o produtor vê e responde os convites ({@code /escritorio}
 *                    desde a #460: o frontend não tem {@code /convites}); vai no e-mail, sem token —
 *                    aceitar exige estar logado com o e-mail convidado
 * @param maxPorDia   teto de convites por escritório em 24 horas (o convite manda e-mail)
 * @param retencao    por quanto tempo o convite fica depois de vencer (o job o apaga depois)
 */
@ConfigurationProperties(prefix = "app.carteira.convite")
public record ConviteCarteiraProperties(
    @DefaultValue("P14D") Duration validade,
    @DefaultValue("http://localhost:4200/escritorio") String urlFrontend,
    @DefaultValue("30") int maxPorDia,
    @DefaultValue("P30D") Duration retencao) {

  /** Valores fora da faixa impedem a subida. */
  public ConviteCarteiraProperties {
    if (validade.compareTo(Duration.ofDays(1)) < 0 || validade.compareTo(Duration.ofDays(60)) > 0) {
      throw new IllegalStateException(
          "app.carteira.convite.validade precisa estar entre 1 e 60 dias.");
    }
    if (maxPorDia < 1) {
      throw new IllegalStateException("app.carteira.convite.max-por-dia precisa ser positivo.");
    }
    if (retencao.isNegative()) {
      throw new IllegalStateException("app.carteira.convite.retencao nao pode ser negativa.");
    }
  }
}
