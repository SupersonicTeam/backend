package br.com.agrocontabil.dto;

import java.util.UUID;

/**
 * Etiqueta do catálogo do escritório (issue #177).
 *
 * @param id   etiqueta
 * @param nome nome como foi digitado na criação
 */
public record EtiquetaResponse(UUID id, String nome) {
}
