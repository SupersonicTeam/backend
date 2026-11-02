package br.com.agrocontabil.repository;

import java.util.UUID;

/**
 * Outra nota do produtor candidata a duplicidade (issue #276): a frase a cita pelo número e pela
 * série, que podem se repetir entre séries.
 *
 * @param id     nota
 * @param numero número
 * @param serie  série
 */
public record OutraNota(UUID id, Long numero, Integer serie) {}
