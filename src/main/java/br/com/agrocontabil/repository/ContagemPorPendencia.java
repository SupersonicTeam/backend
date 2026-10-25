package br.com.agrocontabil.repository;

import java.util.UUID;

/**
 * Quantidade de anexos de uma pendência, saída da consulta agregada das listas (issue #180).
 *
 * @param pendenciaId pendência
 * @param total       quantidade
 */
public record ContagemPorPendencia(UUID pendenciaId, long total) {
}
