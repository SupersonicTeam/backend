package br.com.agrocontabil.assistente.rag;

/**
 * Chaves do {@code metadata} de {@code legislacao_trecho} (issue #156). As colunas geradas do
 * changeSet 0026 leem estas chaves: mudá-las exige migration.
 */
public final class MetadadosTrecho {

  /** Norma, ex.: {@code LC 214/2025}. */
  public static final String NORMA = "norma";
  /** Dispositivo, ex.: {@code art. 138, § 1º}. */
  public static final String DISPOSITIVO = "dispositivo";
  /** Link oficial da norma. */
  public static final String URL = "url";
  /** SHA-256 de norma + dispositivo + texto: idempotência da ingestão. */
  public static final String HASH = "hash";
  /** Início da vigência da redação ({@code AAAA-MM-DD}). */
  public static final String VIGENCIA_INICIO = "vigenciaInicio";
  /** Fim da vigência da redação ({@code AAAA-MM-DD}); {@link #VIGENCIA_ABERTA} se vigente. */
  public static final String VIGENCIA_FIM = "vigenciaFim";
  /** Data em que o texto foi capturado da fonte oficial. */
  public static final String DATA_CAPTURA = "dataCaptura";

  /**
   * Fim de vigência de redação ainda vigente. Data em vez de nulo: o filtro de vigência do
   * {@code PgVectorStore} compara datas ISO como texto e não tem "é nulo".
   */
  public static final String VIGENCIA_ABERTA = "9999-12-31";

  private MetadadosTrecho() {}
}
