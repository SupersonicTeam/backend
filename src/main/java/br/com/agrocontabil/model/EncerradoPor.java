package br.com.agrocontabil.model;

/**
 * Quem encerrou um vínculo da carteira (issue #402). Vínculo encerrado antes do changeSet 0058
 * não tem o registro: o campo fica nulo ("não registrado"), nunca um destes dois valores.
 */
public enum EncerradoPor {
  /** O próprio produtor, por {@code POST /api/vinculos/{id}/encerrar}. */
  PRODUTOR,
  /** O escritório, por {@code POST /api/contador/produtores/{produtorId}/encerrar}. */
  ESCRITORIO
}
