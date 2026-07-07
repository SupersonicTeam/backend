package br.com.agrocontabil.model;

/**
 * Tipo de guia fiscal anexada pelo Contador no painel do Contador (issue #74).
 *
 * <p>Cobre apenas o registro do vencimento e valor informados manualmente pelo Contador —
 * não há geração/validação real de guia (DARF/GPS/FUNRURAL), fora do escopo da issue.
 */
public enum TipoGuiaFiscal {
  ICMS,
  FUNRURAL,
  DARF,
  GPS,
  OUTRO
}
