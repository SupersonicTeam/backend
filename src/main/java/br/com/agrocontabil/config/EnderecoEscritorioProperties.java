package br.com.agrocontabil.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endereço próprio do escritório, {@code {slug}.{dominio-base}} (issue #464), em
 * {@code app.escritorio}.
 *
 * @param dominioBase domínio sob o qual ficam os subdomínios dos escritórios
 *                    ({@code agroconia.com.br}; {@code localhost} em dev)
 * @param endereco    regras da troca do slug
 */
@ConfigurationProperties(prefix = "app.escritorio")
public record EnderecoEscritorioProperties(
    @DefaultValue("agroconia.com.br") String dominioBase,
    @DefaultValue Endereco endereco) {

  /** Valores fora da forma impedem a subida. */
  public EnderecoEscritorioProperties {
    if (dominioBase == null || dominioBase.isBlank()
        || !dominioBase.equals(dominioBase.strip().toLowerCase(Locale.ROOT))
        || dominioBase.startsWith(".") || dominioBase.endsWith(".")) {
      throw new IllegalStateException("app.escritorio.dominio-base precisa ser um dominio em "
          + "minusculas, sem ponto nas pontas (ex.: agroconia.com.br).");
    }
  }

  /**
   * Regras da troca do slug.
   *
   * @param intervaloTroca tempo mínimo entre duas gravações de slug (padrão 30 dias)
   * @param carencia       quanto tempo o slug que sai fica bloqueado para outro escritório
   *                       (padrão 90 dias)
   */
  public record Endereco(
      @DefaultValue("P30D") Duration intervaloTroca,
      @DefaultValue("P90D") Duration carencia) {

    /** Durações não positivas impedem a subida. */
    public Endereco {
      if (intervaloTroca.isNegative() || intervaloTroca.isZero()
          || carencia.isNegative() || carencia.isZero()) {
        throw new IllegalStateException(
            "app.escritorio.endereco.intervalo-troca e carencia precisam ser positivos.");
      }
    }
  }

  /**
   * Host do escritório com esse slug.
   *
   * @param slug slug normalizado
   * @return {@code {slug}.{dominioBase}}
   */
  public String host(String slug) {
    return slug + "." + dominioBase;
  }
}
