package br.com.agrocontabil.model;

/**
 * Função do contador no escritório (issue #228). Fixas na v1.
 *
 * <ul>
 *   <li>{@code ADMINISTRADOR} — gerencia equipe, catálogos e carteira (convidar, suspender,
 *       reativar e encerrar clientes) e atende;
 *   <li>{@code CONTADOR} — atende: ficha, notas, guias, pendências, aprovação de nota (#178);
 *   <li>{@code ASSISTENTE} — atende, mas não aprova nota (#178) nem reabre apuração.
 * </ul>
 */
public enum FuncaoEscritorio {
  ADMINISTRADOR,
  CONTADOR,
  ASSISTENTE
}
