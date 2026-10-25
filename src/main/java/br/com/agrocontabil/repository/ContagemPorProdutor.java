package br.com.agrocontabil.repository;

import java.util.UUID;

/**
 * Contagem de um indicador por produtor, saída das consultas agregadas da carteira (issue #177).
 *
 * @param produtorId produtor
 * @param total      quantidade
 */
public record ContagemPorProdutor(UUID produtorId, long total) {
}
