package br.com.agrocontabil.repository;

import java.util.UUID;

/**
 * Nome de uma etiqueta aplicada a um vínculo da carteira (issue #177).
 *
 * @param vinculoId vínculo
 * @param nome      nome da etiqueta
 */
public record EtiquetaDoVinculo(UUID vinculoId, String nome) {
}
