package br.com.agrocontabil.security;

/**
 * Rotas do endereço próprio do escritório (issue #464): fonte única para os controllers, o
 * {@code SecurityConfig} (que as libera) e os filtros (limite por IP, Bearer ignorado).
 */
public final class RotasEscritorio {

  /** Prefixo do endpoint público do escritório; o slug vem depois. */
  public static final String PUBLICO_PREFIXO = "/api/publico/escritorios/";

  /** Endpoint público do escritório, com o slug. */
  public static final String PUBLICO = PUBLICO_PREFIXO + "{slug}";

  /** "Ask" do On-Demand TLS do Caddy, fora de {@code /api} (só a rede interna o alcança). */
  public static final String TLS_ASK = "/interno/tls/permitido";

  private RotasEscritorio() {
  }
}
