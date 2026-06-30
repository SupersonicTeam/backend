package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.CfopReferencia;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repositório somente leitura da tabela de referência de CFOP (issue #20, RN008).
 */
public interface CfopReferenciaRepository extends JpaRepository<CfopReferencia, String> {

  /**
   * Indica se existe um CFOP de referência com o código informado.
   *
   * @param codigo código CFOP (4 dígitos)
   * @return {@code true} se o código existir na tabela de referência
   */
  boolean existsByCodigo(String codigo);

  /**
   * Retorna os códigos CFOP marcados como aplicáveis ao produtor rural, para carga em cache
   * pelo validador. CFOPs existentes mas inaplicáveis ao produtor rural não entram na lista.
   *
   * @return lista de códigos CFOP aplicáveis ao produtor rural
   */
  @Query("select c.codigo from CfopReferencia c where c.aplicavelProdutorRural = true")
  List<String> findCodigosAplicaveisProdutorRural();
}
