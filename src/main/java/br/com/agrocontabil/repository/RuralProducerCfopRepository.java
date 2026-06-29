package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.RuralProducerCfop;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositório da tabela de referência de CFOPs do producer rural. */
public interface RuralProducerCfopRepository extends JpaRepository<RuralProducerCfop, String> {

  /**
   * Indica se o CFOP informado é aplicável ao producer rural.
   *
   * @param codigo código CFOP (4 dígitos)
   * @return {@code true} se existir na tabela de referência
   */
  boolean existsByCodigo(String codigo);

  /**
   * Busca o CFOP de referência pelo código.
   *
   * @param codigo código CFOP (4 dígitos)
   * @return o CFOP com seus metadados, ou vazio se não cadastrado
   */
  Optional<RuralProducerCfop> findByCodigo(String codigo);
}
