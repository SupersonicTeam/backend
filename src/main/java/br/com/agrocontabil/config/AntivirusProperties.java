package br.com.agrocontabil.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Antivírus dos anexos das pendências (issue #227), em {@code app.pendencias.antivirus}.
 *
 * @param modo           {@code CLAMAV} (padrão) ou {@code SEM_VERIFICACAO} (só dev/test: dá todo
 *                       anexo como limpo, para quem não sobe o ClamAV)
 * @param host           servidor do {@code clamd}
 * @param porta          porta TCP do {@code clamd}
 * @param connectTimeout espera pela conexão
 * @param readTimeout    espera pela resposta, que inclui o tempo de verificação
 * @param loteTamanho    anexos em verificação reprocessados por execução do job
 */
@ConfigurationProperties(prefix = "app.pendencias.antivirus")
public record AntivirusProperties(
    @DefaultValue("CLAMAV") Modo modo,
    @DefaultValue("localhost") String host,
    @DefaultValue("3310") int porta,
    @DefaultValue("PT2S") Duration connectTimeout,
    @DefaultValue("PT30S") Duration readTimeout,
    @DefaultValue("20") int loteTamanho) {

  /** Como os anexos são verificados. */
  public enum Modo {
    CLAMAV,
    SEM_VERIFICACAO
  }

  /** Valor fora da faixa impede a subida. */
  public AntivirusProperties {
    if (porta < 1 || porta > 65_535) {
      throw new IllegalStateException("app.pendencias.antivirus.porta fora de 1..65535.");
    }
    if (connectTimeout.isNegative() || connectTimeout.isZero()
        || readTimeout.isNegative() || readTimeout.isZero()) {
      throw new IllegalStateException(
          "app.pendencias.antivirus.connect-timeout e read-timeout precisam ser positivos.");
    }
    if (loteTamanho < 1) {
      throw new IllegalStateException("app.pendencias.antivirus.lote-tamanho precisa ser >= 1.");
    }
  }
}
