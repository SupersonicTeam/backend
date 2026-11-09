package br.com.agrocontabil.config;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Endereço próprio do escritório, {@code {slug}.{dominio-base}} (issue #464), em
 * {@code app.escritorio}.
 *
 * @param dominioBase domínio sob o qual ficam os subdomínios dos escritórios
 *                    ({@code agroconia.com.br}; {@code localhost} em dev)
 * @param tlsAskChave segredo que o Caddy manda no "ask" do On-Demand TLS
 *                    ({@code APP_ESCRITORIO_TLS_ASK_CHAVE}); vazio = o "ask" recusa tudo
 * @param endereco    regras da troca do slug
 * @param publico     limite do endpoint público do escritório
 */
@ConfigurationProperties(prefix = "app.escritorio")
public record EnderecoEscritorioProperties(
    @DefaultValue("agroconia.com.br") String dominioBase,
    @DefaultValue("") String tlsAskChave,
    @DefaultValue Endereco endereco,
    @DefaultValue Publico publico) {

  /** Tamanho mínimo da chave do "ask", quando definida. */
  static final int CHAVE_MINIMA = 32;

  /** Chave que passa pela query do Caddy sem codificação: nada que a URL altere. */
  private static final Pattern CHAVE_SEGURA_NA_URL =
      Pattern.compile("[A-Za-z0-9_-]{" + CHAVE_MINIMA + ",}");

  /** Valores fora da forma impedem a subida. */
  public EnderecoEscritorioProperties {
    if (dominioBase == null || dominioBase.isBlank()
        || !dominioBase.equals(dominioBase.strip().toLowerCase(Locale.ROOT))
        || dominioBase.startsWith(".") || dominioBase.endsWith(".")) {
      throw new IllegalStateException("app.escritorio.dominio-base precisa ser um dominio em "
          + "minusculas, sem ponto nas pontas (ex.: agroconia.com.br).");
    }
    if (tlsAskChave != null && !tlsAskChave.isBlank()
        && !CHAVE_SEGURA_NA_URL.matcher(tlsAskChave.strip()).matches()) {
      // O Caddy manda a chave na query do ask: `+`, `/`, `=` ou espaco chegariam alterados (o `+`
      // vira espaco) e todo certificado seria recusado em silencio.
      throw new IllegalStateException("app.escritorio.tls-ask-chave precisa de " + CHAVE_MINIMA
          + " caracteres ou mais, so letras, digitos, hifen e sublinhado (gere com: openssl rand "
          + "-hex 32). Vazia desliga o ask.");
    }
  }

  /**
   * Limite do endpoint público do escritório, contra a varredura de slugs.
   *
   * @param porMinuto consultas por minuto por IP (padrão 30)
   * @param maxChaves teto de IPs guardados em memória
   */
  public record Publico(
      @DefaultValue("30") int porMinuto,
      @DefaultValue("10000") int maxChaves) {

    /** Valores não positivos impedem a subida. */
    public Publico {
      if (porMinuto < 1 || maxChaves < 1) {
        throw new IllegalStateException(
            "app.escritorio.publico.por-minuto e max-chaves precisam ser positivos.");
      }
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

  /** Sem a chave do "ask": o {@code toString} de um record a mostraria em log. */
  @Override
  public String toString() {
    return "EnderecoEscritorioProperties[dominioBase=" + dominioBase + ", tlsAskChave="
        + (tlsAskChave == null || tlsAskChave.isBlank() ? "(vazia)" : "(definida)")
        + ", endereco=" + endereco + ", publico=" + publico + "]";
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
