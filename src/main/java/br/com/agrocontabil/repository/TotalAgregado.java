package br.com.agrocontabil.repository;

import java.math.BigDecimal;

/**
 * Quantidade e soma de um mesmo recorte, numa consulta só (issue #169): a Ceres repete a soma e
 * precisa da contagem <em>do mesmo conjunto</em>, senão "suas 7 notas somam R$ X" juntaria números
 * de recortes diferentes.
 *
 * @param quantidade itens no recorte
 * @param valor      soma do valor; {@code null} quando o recorte está vazio
 */
public record TotalAgregado(Long quantidade, BigDecimal valor) {}
