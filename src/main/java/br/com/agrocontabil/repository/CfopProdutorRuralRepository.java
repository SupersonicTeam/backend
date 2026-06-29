package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.CfopProdutorRural;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositório da tabela de referência de CFOPs do produtor rural. */
public interface CfopProdutorRuralRepository extends JpaRepository<CfopProdutorRural, String> {

  /**
   * Indica se o CFOP informado é aplicável ao produtor rural.
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
  Optional<CfopProdutorRural> findByCodigo(String codigo);
}
