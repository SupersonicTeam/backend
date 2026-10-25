package br.com.agrocontabil.dto;

import java.util.UUID;

/**
 * Troca do responsável pelo cliente (issue #228).
 *
 * @param responsavelId membro do escritório; nulo deixa o cliente sem responsável
 */
public record ResponsavelClienteRequest(UUID responsavelId) {
}
