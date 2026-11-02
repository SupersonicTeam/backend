package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.NcmReferencia;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositório somente leitura da tabela de referência de NCM (issue #20, RN008).
 */
public interface NcmReferenciaRepository extends JpaRepository<NcmReferencia, String> {

  /**
   * Indica se existe um NCM de referência com o código informado.
   *
   * @param codigo código NCM (8 dígitos)
   * @return {@code true} se o código existir na tabela de referência
   */
  boolean existsByCodigo(String codigo);

  /**
   * Retorna todos os códigos NCM de referência para carga em cache pelo validador.
   *
   * @return lista de códigos NCM
   */
  @Query("select n.codigo from NcmReferencia n")
  List<String> findAllCodigos();

  /**
   * Indica se o NCM é de produto agropecuário <em>in natura</em> (LC 214/2025, art. 137) — issue
   * #272.
   *
   * @param codigo código NCM (8 dígitos)
   * @return {@code true} se o código consta na referência marcado como in natura
   */
  boolean existsByCodigoAndInNaturaTrue(String codigo);

  /**
   * Quais dos códigos são in natura (issue #283, consulta em lote).
   *
   * @param codigos códigos NCM (8 dígitos)
   * @return os marcados como in natura
   */
  @Query("select n.codigo from NcmReferencia n where n.codigo in :codigos and n.inNatura = true")
  List<String> findCodigosInNatura(@Param("codigos") Collection<String> codigos);
}
