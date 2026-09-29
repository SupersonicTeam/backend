package br.com.agrocontabil.service;

import br.com.agrocontabil.repository.CompraInsumoAgregado;
import java.math.BigDecimal;
import java.util.List;

/**
 * Compras de um insumo no período (issue #142).
 *
 * @param valorTotal      soma do valor dos itens (2 casas; zero sem compras)
 * @param quantidadeNotas notas de entrada distintas em que o insumo aparece
 * @param porUnidade      quantidade e valor por unidade de medida, maior valor primeiro
 */
public record ComprasInsumoResultado(
    BigDecimal valorTotal, long quantidadeNotas, List<CompraInsumoAgregado> porUnidade) {

  /** Cópia defensiva: o resultado é imutável. */
  public ComprasInsumoResultado {
    porUnidade = List.copyOf(porUnidade);
  }
}
